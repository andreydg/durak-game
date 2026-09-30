import { describe, it, expect } from "vitest";
// Importing for its side effect: under jsdom, logic.js assigns window.DurakLogic.
import "../../src/main/resources/static/js/logic.js";

const L = window.DurakLogic;

describe("prettyCard", () => {
    it("renders rank with a suit glyph", () => {
        expect(L.prettyCard("6S")).toBe("6♠");
        expect(L.prettyCard("10H")).toBe("10♥");
        expect(L.prettyCard("AD")).toBe("A♦");
        expect(L.prettyCard("KC")).toBe("K♣");
    });

    it("returns a dash for empty input", () => {
        expect(L.prettyCard("")).toBe("-");
        expect(L.prettyCard(null)).toBe("-");
        expect(L.prettyCard(undefined)).toBe("-");
    });

    it("falls back to the raw suit letter when unknown", () => {
        expect(L.prettyCard("6X")).toBe("6X");
    });
});

describe("sortCardCodesByRank", () => {
    it("orders by rank then suit", () => {
        expect(L.sortCardCodesByRank(["AS", "6C", "10D", "6S"]))
            .toEqual(["6C", "6S", "10D", "AS"]);
    });

    it("keeps 10 between 9 and J (string vs numeric rank)", () => {
        expect(L.sortCardCodesByRank(["JH", "10H", "9H"]))
            .toEqual(["9H", "10H", "JH"]);
    });

    it("does not mutate the input array", () => {
        const input = ["AS", "6C"];
        L.sortCardCodesByRank(input);
        expect(input).toEqual(["AS", "6C"]);
    });

    it("handles empty / nullish input", () => {
        expect(L.sortCardCodesByRank([])).toEqual([]);
        expect(L.sortCardCodesByRank(null)).toEqual([]);
        expect(L.sortCardCodesByRank(undefined)).toEqual([]);
    });

    it("pushes unknown ranks/suits to the end deterministically", () => {
        expect(L.sortCardCodesByRank(["ZZ", "6C"])).toEqual(["6C", "ZZ"]);
    });
});

describe("trumpSuitGlyph", () => {
    it("maps single-letter suit codes", () => {
        expect(L.trumpSuitGlyph("S")).toBe("♠");
        expect(L.trumpSuitGlyph("h")).toBe("♥");
    });

    it("maps full suit names", () => {
        expect(L.trumpSuitGlyph("SPADES")).toBe("♠");
        expect(L.trumpSuitGlyph("diamonds")).toBe("♦");
    });

    it("returns empty string for falsy input", () => {
        expect(L.trumpSuitGlyph("")).toBe("");
        expect(L.trumpSuitGlyph(null)).toBe("");
    });

    it("echoes unknown codes", () => {
        expect(L.trumpSuitGlyph("XY")).toBe("XY");
    });
});

describe("displayStatus", () => {
    it("maps known statuses to friendly text", () => {
        expect(L.displayStatus("LOBBY")).toBe("Lobby");
        expect(L.displayStatus("IN_PROGRESS")).toBe("In progress");
        expect(L.displayStatus("FINISHED")).toBe("Finished");
    });

    it("title-cases unknown statuses", () => {
        expect(L.displayStatus("SOME_OTHER_STATE")).toBe("Some Other State");
    });

    it("handles empty input", () => {
        expect(L.displayStatus("")).toBe("");
        expect(L.displayStatus(null)).toBe("");
    });
});

describe("shouldReplaceRefreshTimer", () => {
    it("preserves an earlier catch-up deadline from a later health poll", () => {
        expect(L.shouldReplaceRefreshTimer(1_200, 30_000)).toBe(false);
    });

    it("allows prompt fallback work to replace a later health poll", () => {
        expect(L.shouldReplaceRefreshTimer(30_000, 1_200)).toBe(true);
    });

    it("allows explicit rescheduling and empty timer slots", () => {
        expect(L.shouldReplaceRefreshTimer(1_200, 30_000, true)).toBe(true);
        expect(L.shouldReplaceRefreshTimer(0, 30_000)).toBe(true);
    });
});

describe("shouldAcceptGameVersion", () => {
    it("rejects a delayed snapshot older than current action state", () => {
        expect(L.shouldAcceptGameVersion(10, 9)).toBe(false);
    });

    it("accepts equal and newer snapshots", () => {
        expect(L.shouldAcceptGameVersion(10, 10)).toBe(true);
        expect(L.shouldAcceptGameVersion(10, 11)).toBe(true);
    });

    it("accepts snapshots when either version is unavailable", () => {
        expect(L.shouldAcceptGameVersion(undefined, 1)).toBe(true);
        expect(L.shouldAcceptGameVersion(1, undefined)).toBe(true);
    });
});

describe("shouldApplySnapshot", () => {
    const game = (code, version) => ({ code, version, players: [] });

    it("applies the first snapshot and snapshots of another room", () => {
        expect(L.shouldApplySnapshot(null, game("ABC234", 3))).toBe(true);
        expect(L.shouldApplySnapshot(game("ABC234", 9), game("XYZ789", 1))).toBe(true);
    });

    it("never lets an older version of the same room replace newer state", () => {
        expect(L.shouldApplySnapshot(game("ABC234", 14), game("ABC234", 13))).toBe(false);
        expect(L.shouldApplySnapshot(game("ABC234", 14), game("ABC234", 14))).toBe(true);
        expect(L.shouldApplySnapshot(game("ABC234", 14), game("ABC234", 15))).toBe(true);
    });

    it("rejects values that are not game snapshots", () => {
        expect(L.shouldApplySnapshot(null, null)).toBe(false);
        expect(L.shouldApplySnapshot(null, { code: "ABC234", version: 1 })).toBe(false);
        expect(L.shouldApplySnapshot(null, { version: 1, players: [] })).toBe(false);
        expect(L.shouldApplySnapshot(null, "<html>")).toBe(false);
    });
});

describe("parseJsonBody", () => {
    it("decodes JSON bodies, including problem+json and charset variants", () => {
        expect(L.parseJsonBody("application/json", "{\"a\":1}")).toEqual({ ok: true, value: { a: 1 } });
        expect(L.parseJsonBody("application/json;charset=UTF-8", "[1]")).toEqual({ ok: true, value: [1] });
        expect(L.parseJsonBody("application/problem+json", "{\"message\":\"x\"}").value).toEqual({ message: "x" });
    });

    it("treats an empty body as null whatever its type (for example /leave)", () => {
        expect(L.parseJsonBody(null, "")).toEqual({ ok: true, value: null });
        expect(L.parseJsonBody("application/json", "  ")).toEqual({ ok: true, value: null });
    });

    it("reports HTML error pages and malformed JSON as unreadable instead of throwing", () => {
        expect(L.parseJsonBody("text/html", "<html><body>502 Bad Gateway</body></html>")).toEqual({ ok: false, value: null });
        expect(L.parseJsonBody("application/json", "<html>")).toEqual({ ok: false, value: null });
        expect(L.parseJsonBody("", "{\"a\":1}")).toEqual({ ok: false, value: null });
    });
});

describe("apiErrorMessage", () => {
    it("prefers the server's message", () => {
        expect(L.apiErrorMessage(409, { message: "Not your turn" })).toBe("Not your turn");
        expect(L.apiErrorMessage(503, { message: " Temporary outage " })).toBe("Temporary outage");
    });

    it("never surfaces a parser error for bodies without a message", () => {
        expect(L.apiErrorMessage(502, null)).toBe("The server is unavailable right now. Please try again.");
        expect(L.apiErrorMessage(0, null)).toContain("Could not reach the server");
        expect(L.apiErrorMessage(404, {})).toBe("Game not found");
        expect(L.apiErrorMessage(410, null)).toContain("expired");
        expect(L.apiErrorMessage(429, null)).toContain("Too many requests");
        expect(L.apiErrorMessage(400, { message: 42 })).toBe("Request failed. Please try again.");
    });
});

describe("sessionErrorKind", () => {
    it("classifies failures by status, not message text", () => {
        expect(L.sessionErrorKind(403)).toBe("seat-invalid");
        expect(L.sessionErrorKind(404)).toBe("room-gone");
        expect(L.sessionErrorKind(410)).toBe("room-gone");
        for (const status of [0, 400, 409, 429, 500, 502, 503, undefined]) {
            expect(L.sessionErrorKind(status)).toBe("transient");
        }
    });
});

describe("seatProblem", () => {
    const players = (meHand, meHandSize = meHand.length) => [
        { id: "me", hand: meHand, handSize: meHandSize },
        { id: "bot", hand: [], handSize: 6 }
    ];

    it("accepts a seat that can see its own hand", () => {
        expect(L.seatProblem({ status: "IN_PROGRESS", players: players(["6C", "7C"]) }, "me")).toBeNull();
    });

    it("flags an in-progress view without the viewer's cards as unauthorized", () => {
        expect(L.seatProblem({ status: "IN_PROGRESS", players: players([], 6) }, "me")).toBe("unauthorized");
        expect(L.seatProblem({ status: "IN_PROGRESS", players: [{ id: "me", handSize: 3 }] }, "me")).toBe("unauthorized");
    });

    it("does not flag a viewer who is simply out of cards", () => {
        expect(L.seatProblem({ status: "IN_PROGRESS", players: players([], 0) }, "me")).toBeNull();
        expect(L.seatProblem({ status: "FINISHED", players: players([], 0) }, "me")).toBeNull();
    });

    it("flags a host who could start but is not allowed to", () => {
        const lobby = { status: "LOBBY", hostPlayerId: "me", players: players([], 0), legalMoves: { canStart: false } };
        expect(L.seatProblem(lobby, "me")).toBe("unauthorized");
        expect(L.seatProblem({ ...lobby, legalMoves: { canStart: true } }, "me")).toBeNull();
        expect(L.seatProblem({ ...lobby, players: [lobby.players[0]] }, "me")).toBeNull();
        expect(L.seatProblem({ ...lobby, hostPlayerId: "bot" }, "me")).toBeNull();
    });

    it("flags a viewer who is not seated at all", () => {
        expect(L.seatProblem({ status: "LOBBY", players: players([]) }, "someone-else")).toBe("not-seated");
    });

    it("ignores missing input", () => {
        expect(L.seatProblem(null, "me")).toBeNull();
        expect(L.seatProblem({ status: "LOBBY", players: [] }, "")).toBeNull();
    });
});

describe("neighborCardCode", () => {
    const before = ["6C", "7D", "8H", "9S", "JC"];

    it("keeps a card that is still in the hand", () => {
        expect(L.neighborCardCode(before, "8H", ["6C", "8H", "9S"])).toBe("8H");
    });

    it("moves to the card that slid into the played card's slot", () => {
        expect(L.neighborCardCode(before, "8H", ["6C", "7D", "9S", "JC"])).toBe("9S");
        expect(L.neighborCardCode(before, "6C", ["7D", "8H", "9S", "JC"])).toBe("7D");
    });

    it("falls back to the left neighbour at the end of the hand", () => {
        expect(L.neighborCardCode(before, "JC", ["6C", "7D", "8H", "9S"])).toBe("9S");
    });

    it("skips neighbours that also left the hand", () => {
        expect(L.neighborCardCode(before, "8H", ["6C", "JC", "AS"])).toBe("JC");
    });

    it("handles unknown cards and empty hands", () => {
        expect(L.neighborCardCode(before, "AS", ["7D", "8H"])).toBe("7D");
        expect(L.neighborCardCode(before, "8H", [])).toBeNull();
        expect(L.neighborCardCode(null, "8H", ["QD"])).toBe("QD");
    });
});

describe("focusRecoveryTarget", () => {
    const handBefore = ["6C", "7D", "8H"];

    it("sends a removed card's focus to its neighbour", () => {
        expect(L.focusRecoveryTarget({
            lost: { kind: "card", code: "7D" }, handBefore, handAfter: ["6C", "8H"]
        })).toEqual({ kind: "card", code: "8H" });
    });

    it("sends a disabled play button's focus next to the card that was played", () => {
        expect(L.focusRecoveryTarget({
            lost: { kind: "control", id: "attackBtn" },
            handBefore,
            handAfter: ["6C", "8H"],
            lastPlayedCard: "7D"
        })).toEqual({ kind: "card", code: "8H" });
    });

    it("sends Take/End round focus to the hand, not to another action", () => {
        expect(L.focusRecoveryTarget({
            lost: { kind: "control", id: "takeBtn" },
            handBefore,
            handAfter: handBefore,
            availableControls: ["shareBtn"]
        })).toEqual({ kind: "card", code: "6C" });
    });

    it("keeps a control that is still usable", () => {
        expect(L.focusRecoveryTarget({
            lost: { kind: "control", id: "endRoundBtn" }, stillAvailable: true
        })).toEqual({ kind: "control", id: "endRoundBtn" });
    });

    it("moves a vanished room control to the next safe control, then the heading", () => {
        expect(L.focusRecoveryTarget({
            lost: { kind: "control", id: "addBotBtn" }, availableControls: ["startBtn", "shareBtn"]
        })).toEqual({ kind: "control", id: "startBtn" });
        expect(L.focusRecoveryTarget({ lost: { kind: "control", id: "addBotBtn" } }))
            .toEqual({ kind: "heading" });
        expect(L.focusRecoveryTarget({ lost: { kind: "card", code: "6C" }, handBefore: ["6C"], handAfter: [] }))
            .toEqual({ kind: "heading" });
    });
});

describe("viewKey", () => {
    it("names each screen", () => {
        expect(L.viewKey({ hasSession: false })).toBe("lobby");
        expect(L.viewKey({ hasSession: false, reconnecting: true })).toBe("reconnecting");
        expect(L.viewKey({ hasSession: true, status: "LOBBY" })).toBe("room");
        expect(L.viewKey({ hasSession: true, status: "IN_PROGRESS" })).toBe("table");
        expect(L.viewKey({ hasSession: true, status: "FINISHED" })).toBe("result");
    });
});

describe("room invite links", () => {
    it("reads and normalizes a valid room query", () => {
        expect(L.roomCodeFromSearch("?room=abc123")).toBe("ABC123");
        expect(L.roomCodeFromSearch("?foo=1&room=XY9Z88")).toBe("XY9Z88");
    });

    it("rejects missing or malformed invite codes", () => {
        expect(L.roomCodeFromSearch("?room=short")).toBe("");
        expect(L.roomCodeFromSearch("?room=%3Cscript%3E")).toBe("");
        expect(L.roomCodeFromSearch("")).toBe("");
    });

    it("builds a canonical same-origin invite URL", () => {
        expect(L.buildInviteUrl("https://durak.example", "abc123"))
            .toBe("https://durak.example/?room=ABC123");
        expect(L.buildInviteUrl("https://durak.example/old/path", "bad"))
            .toBe("");
    });

    it("removes invite state without reserializing unrelated query parameters", () => {
        expect(L.searchWithoutRoomParam("?room=ABC123&utm_source=invite"))
            .toBe("?utm_source=invite");
        expect(L.searchWithoutRoomParam("?utm_source=a%20b&room=ABC123&flag"))
            .toBe("?utm_source=a%20b&flag");
        expect(L.searchWithoutRoomParam("?room=ABC123")).toBe("");
    });

    it("preserves malformed and similarly named query parameters", () => {
        expect(L.searchWithoutRoomParam("?%E0%A4%A=x&roommate=one&room=ABC123"))
            .toBe("?%E0%A4%A=x&roommate=one");
    });
});

describe("escapeHtml", () => {
    it("escapes angle brackets and ampersands", () => {
        expect(L.escapeHtml("<script>")).not.toContain("<script>");
        expect(L.escapeHtml("a & b")).toContain("&amp;");
    });

    it("stringifies nullish input safely", () => {
        expect(L.escapeHtml(null)).toBe("");
        expect(L.escapeHtml(undefined)).toBe("");
    });

    it("escapes both quote characters so output is safe inside attributes", () => {
        expect(L.escapeHtml(`"x" onmouseover='y'`)).toBe("&quot;x&quot; onmouseover=&#39;y&#39;");
        expect(L.escapeHtml(`<a href="x">&</a>`)).toBe("&lt;a href=&quot;x&quot;&gt;&amp;&lt;/a&gt;");
    });

    it("stringifies numbers", () => {
        expect(L.escapeHtml(4)).toBe("4");
        expect(L.escapeHtml(0)).toBe("0");
    });
});

describe("normalizeRoomCode", () => {
    it("accepts six characters from the server alphabet, case-insensitively", () => {
        expect(L.normalizeRoomCode("ABC234")).toBe("ABC234");
        expect(L.normalizeRoomCode("  xyz789 ")).toBe("XYZ789");
        expect(L.normalizeRoomCode("hjkmnp")).toBe("HJKMNP");
    });

    it("rejects characters the server never generates", () => {
        expect(L.normalizeRoomCode("NOPE12")).toBe("");  // O and 1
        expect(L.normalizeRoomCode("ABCDI2")).toBe("");  // I
        expect(L.normalizeRoomCode("ABC0Z2")).toBe("");  // 0
    });

    it("rejects wrong lengths and anything that could alter a request path", () => {
        expect(L.normalizeRoomCode("ABC23")).toBe("");
        expect(L.normalizeRoomCode("ABC2345")).toBe("");
        expect(L.normalizeRoomCode("../bot")).toBe("");
        expect(L.normalizeRoomCode("AB/C23")).toBe("");
        expect(L.normalizeRoomCode("ABC 23")).toBe("");
        expect(L.normalizeRoomCode("ABC23?")).toBe("");
        expect(L.normalizeRoomCode("")).toBe("");
        expect(L.normalizeRoomCode(null)).toBe("");
        expect(L.normalizeRoomCode(undefined)).toBe("");
    });
});

describe("isCardCode", () => {
    it("accepts every dealt card", () => {
        for (const rank of ["6", "7", "8", "9", "10", "J", "Q", "K", "A"]) {
            for (const suit of ["C", "D", "H", "S"]) {
                expect(L.isCardCode(rank + suit)).toBe(true);
            }
        }
    });

    it("rejects anything else", () => {
        for (const bad of ["", "5C", "1C", "11C", "10X", "6c", "AS\"", "AS><img", "BACK", null, undefined, 6]) {
            expect(L.isCardCode(bad)).toBe(false);
        }
    });
});

describe("playerTeam", () => {
    const game = {
        players: [
            { id: "a", team: 0 },
            { id: "b", team: 1 },
            { id: "c", team: null }
        ]
    };

    it("returns the team of a known player", () => {
        expect(L.playerTeam(game, "a")).toBe(0);
        expect(L.playerTeam(game, "b")).toBe(1);
    });

    it("returns null for teamless or unknown players", () => {
        expect(L.playerTeam(game, "c")).toBeNull();
        expect(L.playerTeam(game, "zzz")).toBeNull();
        expect(L.playerTeam(undefined, "a")).toBeNull();
    });
});

describe("onAttackingSide", () => {
    it("is false for the defender", () => {
        const game = { defenderPlayerId: "d", players: [{ id: "d" }, { id: "a" }] };
        expect(L.onAttackingSide(game, "d")).toBe(false);
    });

    it("is true for any non-defender in a non-team game", () => {
        const game = { defenderPlayerId: "d", players: [{ id: "d" }, { id: "a" }, { id: "b" }] };
        expect(L.onAttackingSide(game, "a")).toBe(true);
        expect(L.onAttackingSide(game, "b")).toBe(true);
    });

    it("uses opposing-team logic in a 4-player team game", () => {
        const game = {
            defenderPlayerId: "d",
            players: [
                { id: "d", team: 1 },
                { id: "p1", team: 0 },
                { id: "mate", team: 1 },
                { id: "p2", team: 0 }
            ]
        };
        expect(L.onAttackingSide(game, "p1")).toBe(true);   // opposite team -> attacker
        expect(L.onAttackingSide(game, "p2")).toBe(true);
        expect(L.onAttackingSide(game, "mate")).toBe(false); // defender's teammate -> not attacking
    });

    it("is false when inputs are missing", () => {
        expect(L.onAttackingSide(null, "a")).toBe(false);
        expect(L.onAttackingSide({ defenderPlayerId: "d", players: [] }, null)).toBe(false);
    });
});

describe("roleTags", () => {
    it("marks attacker and defender during play", () => {
        const game = {
            status: "IN_PROGRESS",
            attackerPlayerId: "a",
            defenderPlayerId: "d",
            takingCardsInProgress: false
        };
        expect(L.roleTags({ id: "a" }, game)).toContain("⚔️");
        expect(L.roleTags({ id: "d" }, game)).toContain("🛡️");
    });

    it("adds the taking glyph for the taking defender", () => {
        const game = {
            status: "IN_PROGRESS",
            attackerPlayerId: "a",
            defenderPlayerId: "d",
            takingCardsInProgress: true,
            takingPlayerId: "d"
        };
        expect(L.roleTags({ id: "d" }, game)).toContain("⇩");
    });

    it("marks the loser when finished", () => {
        const game = { status: "FINISHED", loserPlayerId: "x" };
        expect(L.roleTags({ id: "x" }, game)).toContain("🤡");
        expect(L.roleTags({ id: "y" }, game)).toBe("");
    });

    it("appends team labels", () => {
        const game = { status: "IN_PROGRESS", attackerPlayerId: "a", defenderPlayerId: "d" };
        expect(L.roleTags({ id: "a", team: 0 }, game)).toContain("team 0");
    });
});

describe("gameResult", () => {
    const players = [
        { id: "a", name: "Alice", team: null },
        { id: "b", name: "Boris", team: null }
    ];

    it("returns a personal win for a non-loser", () => {
        const result = L.gameResult({ status: "FINISHED", loserPlayerId: "b", players }, "a");
        expect(result.outcome).toBe("win");
        expect(result.title).toBe("You won!");
        expect(result.summary).toContain("Boris");
    });

    it("returns a personal loss for the durak", () => {
        const result = L.gameResult({ status: "FINISHED", loserPlayerId: "b", players }, "b");
        expect(result.outcome).toBe("loss");
        expect(result.title).toContain("durak");
    });

    it("reports a draw when nobody is left holding cards", () => {
        const result = L.gameResult({ status: "FINISHED", loserPlayerId: null, players }, "a");
        expect(result.outcome).toBe("draw");
        expect(result.title).toContain("Nobody");
    });

    it("scores four-player results by team", () => {
        const teamPlayers = [
            { id: "a", name: "A", team: 0 },
            { id: "b", name: "B", team: 1 },
            { id: "c", name: "C", team: 0 },
            { id: "d", name: "D", team: 1 }
        ];
        const game = { status: "FINISHED", loserPlayerId: "d", players: teamPlayers };
        expect(L.gameResult(game, "a").outcome).toBe("win");
        expect(L.gameResult(game, "b").outcome).toBe("loss");
    });

    it("preserves a personal win after the loser leaves", () => {
        const result = L.gameResult({
            status: "FINISHED",
            loserPlayerId: "b",
            loserPlayerName: "Boris",
            loserTeam: null,
            players: [players[0]]
        }, "a");

        expect(result.outcome).toBe("win");
        expect(result.title).toBe("You won!");
        expect(result.summary).toContain("Boris");
    });

    it("scores a shrunken team roster from the durable losing team", () => {
        const game = {
            status: "FINISHED",
            loserPlayerId: "d",
            loserPlayerName: "D",
            loserTeam: 1,
            players: [
                { id: "a", name: "A", team: 0 },
                { id: "b", name: "B", team: 1 },
                { id: "c", name: "C", team: 0 }
            ]
        };

        expect(L.gameResult(game, "a").outcome).toBe("win");
        expect(L.gameResult(game, "b").outcome).toBe("loss");
    });

    it("does not misreport a legacy dangling loser as a draw", () => {
        const result = L.gameResult({
            status: "FINISHED",
            loserPlayerId: "departed",
            players: [players[0]]
        }, "a");

        expect(result.outcome).not.toBe("draw");
        expect(result.title).toContain("left the table");
    });

    it("returns null before a game is finished", () => {
        expect(L.gameResult({ status: "IN_PROGRESS", players }, "a")).toBeNull();
    });
});

describe("lobbyRowsHtml", () => {
    const rows = [
        { code: "ABC123", playerNames: ["Alice", "Bob"], playerCount: 2, maxPlayers: 4 },
        { code: "XYZ789", playerNames: ["Cara"], playerCount: 1, maxPlayers: 4 }
    ];

    it("returns empty string with no rows", () => {
        expect(L.lobbyRowsHtml([], true, null)).toBe("");
    });

    it("renders a Join button in interactive mode", () => {
        const html = L.lobbyRowsHtml(rows, true, null);
        expect(html).toContain("lobby-list-join");
        expect(html).toContain('data-code="ABC123"');
        expect(html).toContain("2/4 players");
    });

    it("renders 'This room' for the current code and no Join button", () => {
        const html = L.lobbyRowsHtml(rows, false, "abc123");
        expect(html).toContain("This room");
        expect(html).toContain("lobby-list-item--yours");
        expect(html).not.toContain("lobby-list-join");
    });

    it("escapes player names to prevent HTML injection", () => {
        const evil = [{ code: "EVL000", playerNames: ["<img src=x>"], playerCount: 1, maxPlayers: 4 }];
        const html = L.lobbyRowsHtml(evil, true, null);
        expect(html).not.toContain("<img src=x>");
        expect(html).toContain("&lt;img");
    });

    it("cannot break out of the data-code attribute", () => {
        const evil = [{ code: `X" onclick="alert(1)`, playerNames: [], playerCount: "1<b>", maxPlayers: 4 }];
        const container = document.createElement("div");
        container.innerHTML = L.lobbyRowsHtml(evil, true, null);
        const button = container.querySelector(".lobby-list-join");
        expect(button.getAttribute("onclick")).toBeNull();
        expect(button.getAttribute("data-code")).toBe(`X" onclick="alert(1)`);
        expect(container.querySelector("b")).toBeNull();
    });
});

describe("realtime fallback timing", () => {
    it("uses exponential reconnect backoff and caps it at 30 seconds", () => {
        expect(L.reconnectDelayMs(0, 0.5)).toBe(1_000);
        expect(L.reconnectDelayMs(1, 0.5)).toBe(2_000);
        expect(L.reconnectDelayMs(4, 0.5)).toBe(16_000);
        expect(L.reconnectDelayMs(20, 1)).toBe(30_000);
    });

    it("adds bounded jitter without allowing negative delays", () => {
        expect(L.reconnectDelayMs(0, 0)).toBe(800);
        expect(L.reconnectDelayMs(0, 1)).toBe(1_200);
        expect(L.reconnectDelayMs(-5, -1)).toBe(800);
    });

    it("backs game HTTP refreshes off when the websocket is healthy", () => {
        expect(L.gameRefreshDelayMs(false, "visible")).toBe(3_000);
        expect(L.gameRefreshDelayMs(true, "visible")).toBe(30_000);
    });

    it("pauses game refreshes in hidden tabs", () => {
        expect(L.gameRefreshDelayMs(false, "hidden")).toBeNull();
        expect(L.gameRefreshDelayMs(true, "hidden")).toBeNull();
    });

    it("uses a long lobby health interval when invalidations are connected", () => {
        expect(L.lobbyRefreshDelayMs(true, 0, "visible")).toBe(60_000);
    });

    it("backs failed lobby fallback reads off and caps them", () => {
        expect(L.lobbyRefreshDelayMs(false, 0, "visible")).toBe(4_000);
        expect(L.lobbyRefreshDelayMs(false, 1, "visible")).toBe(8_000);
        expect(L.lobbyRefreshDelayMs(false, 2, "visible")).toBe(16_000);
        expect(L.lobbyRefreshDelayMs(false, 99, "visible")).toBe(30_000);
    });

    it("pauses lobby health reads in hidden tabs", () => {
        expect(L.lobbyRefreshDelayMs(false, 0, "hidden")).toBeNull();
        expect(L.lobbyRefreshDelayMs(true, 0, "hidden")).toBeNull();
    });
});
