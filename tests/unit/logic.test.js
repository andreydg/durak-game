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
});
