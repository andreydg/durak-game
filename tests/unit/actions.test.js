import {afterEach, beforeEach, describe, expect, it, vi} from "vitest";
import {readFileSync} from "node:fs";
import {syntheticGame} from "../e2e/support/synthetic.js";

// Exercise the real action, HTTP serialization, snapshot gate and alert rendering without
// starting background polling or sockets. Playwright covers the actual button binding.
vi.mock("../../src/main/resources/static/js/sync.js", () => ({
    beginPolling: vi.fn(),
    closeGameSocket: vi.fn(),
    connectGameSocket: vi.fn(),
    scheduleGameRefresh: vi.fn(),
    stopPolling: vi.fn(),
    syncBotThinkingFromGame: vi.fn()
}));

const html = readFileSync("src/main/resources/static/index.html", "utf8");
let actions, state, view, fetchMock;

function coveredGame(overrides = {}) {
    return syntheticGame({
        version: 12,
        boutId: "deal:1",
        canAutoEndRound: true,
        attackerPlayerId: "me",
        defenderPlayerId: "p2",
        hand: ["6D"],
        opponentHandSizes: [0],
        table: [{attackCard: "6H", defenseCard: "7H", attackerId: "me"}],
        legalMoves: {canEndRound: true},
        ...overrides
    });
}

function jsonResponse(game) {
    return new Response(JSON.stringify(game), {status: 200, headers: {"Content-Type": "application/json"}});
}

beforeEach(async () => {
    vi.resetModules();
    vi.clearAllMocks();
    sessionStorage.clear();
    document.body.innerHTML = new DOMParser().parseFromString(html, "text/html").body.innerHTML;
    const {seat} = await import("../../src/main/resources/static/js/api.js");
    Object.assign(seat, {gameCode: "SYNTH2", playerId: "me", playerToken: "me-secret"});
    ({state} = await import("../../src/main/resources/static/js/state.js"));
    actions = await import("../../src/main/resources/static/js/actions.js");
    view = await import("../../src/main/resources/static/js/view.js");
    fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
    vi.unstubAllGlobals();
    sessionStorage.clear();
});

describe("End round", () => {
    it("shows a legacy request's reload-required conflict and refetches without clearing the warning", async () => {
        const displayed = coveredGame({canAutoEndRound: false, opponentHandSizes: [1]});
        actions.applyGameSnapshot(displayed);
        const message = "This page is out of date. Reload to continue.";
        fetchMock.mockResolvedValue(new Response(JSON.stringify({message}), {
            status: 409, headers: {"Content-Type": "application/json"}
        }));
        const {api, gamePath, seat} = await import("../../src/main/resources/static/js/api.js");
        const {scheduleGameRefresh} = await import("../../src/main/resources/static/js/sync.js");

        // Pre-deploy actions send only playerId; runAction's 409 path is unchanged from main.
        expect(await actions.runAction("End round", () =>
            api(gamePath(seat.gameCode, "/end-round"), "POST", {playerId: seat.playerId}))).toBe(false);
        expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({playerId: "me"});
        expect(scheduleGameRefresh).toHaveBeenCalledWith(0, true);
        expect(state.actionInFlight).toBeNull();
        actions.applyGameSnapshot(displayed); // A successful refetch must not hide the reload instruction.
        const alert = document.getElementById("appAlert");
        expect(alert.textContent).toBe(`End round: ${message}`);
        expect(alert.classList.contains("hidden")).toBe(false);
        expect(state.game).toEqual(displayed);
    });

    it.each([false, true])("sends the displayed identity when canAutoEndRound is %s", async canAutoEndRound => {
        const displayed = coveredGame({canAutoEndRound});
        actions.applyGameSnapshot(displayed);
        const next = syntheticGame({version: 13, boutId: "deal:2"});
        fetchMock.mockResolvedValue(jsonResponse(next));

        // Even a snapshot arriving in a render hook before the request is dispatched must
        // not change which bout the player's press referred to.
        view.onRendered(() => { state.game = next; });
        expect(await actions.endRound()).toBe(true);

        expect(fetchMock).toHaveBeenCalledTimes(1);
        const [path, request] = fetchMock.mock.calls[0];
        expect(path).toBe("/api/games/SYNTH2/end-round");
        expect(request.method).toBe("POST");
        expect(request.headers["X-Durak-Token"]).toBe("me-secret");
        expect(JSON.parse(request.body)).toEqual({playerId: "me", boutId: displayed.boutId});
    });

    it.each(["current", "older", "finished"])("a stale End round returning %s state settles without an alert or rollback", async responseKind => {
        const displayed = coveredGame();
        actions.applyGameSnapshot(displayed);
        view.showError("A previous action failed.");
        let release;
        fetchMock.mockReturnValue(new Promise(resolve => { release = resolve; }));

        const pending = actions.endRound();
        expect(state.actionInFlight).toEqual({name: "End round"});
        expect(document.getElementById("appAlert").classList.contains("hidden")).toBe(true);
        const [, request] = fetchMock.mock.calls[0];
        expect(JSON.parse(request.body)).toEqual({playerId: "me", boutId: "deal:1"});

        // Another client/server timer ended the displayed bout while this POST was pending.
        const newer = responseKind === "finished"
            ? syntheticGame({version: 14, boutId: "deal:2", status: "FINISHED", loserPlayerId: "me"})
            : coveredGame({version: 14, boutId: "deal:2",
                table: [{attackCard: "8H", defenseCard: "9H", attackerId: "me"}]});
        expect(actions.applyGameSnapshot(newer)).toBe(true);
        expect(state.game).toEqual(newer);
        expect(await actions.endRound()).toBe(false);
        expect(fetchMock).toHaveBeenCalledTimes(1);
        expect(JSON.parse(request.body)).toEqual({playerId: "me", boutId: "deal:1"});

        // A stale-bout no-op is a 200 snapshot, not a conflict. If that snapshot is itself
        // delayed, the normal version gate must keep the newer snapshot already on screen.
        const response = responseKind === "older"
            ? syntheticGame({version: 13, boutId: "deal:2"}) : newer;
        release(jsonResponse(response));
        expect(await pending).toBe(true);
        expect(state.actionInFlight).toBeNull();
        expect(state.game).toEqual(newer);
        const alert = document.getElementById("appAlert");
        expect(alert.textContent).toBe("");
        expect(alert.classList.contains("hidden")).toBe(true);
        if (responseKind === "finished") {
            expect(document.getElementById("resultPanel").classList.contains("hidden")).toBe(false);
        } else {
            expect(document.querySelector("#battleCards .battle-pair").textContent)
                .toBe("8 of hearts, beaten by 9 of hearts");
            expect(document.getElementById("endRoundBtn").disabled).toBe(false);
        }
    });
});
