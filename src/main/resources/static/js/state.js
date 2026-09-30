/*
 * Shared, mutable UI state of the page. The saved seat (room code, player id, token) lives in
 * api.js; timers and sockets stay private to the modules that own them.
 */
export const state = {
    /* The last GameResponse accepted for the seat (see actions.applyGameSnapshot). */
    game: null,
    selectedHandCard: null,
    showGameplayHelp: false,
    /* Set when the server no longer accepts this browser's seat (wrong/missing token, not seated). */
    seatInvalid: false,
    /* {name} of the one user action whose request is pending; repeat activations are ignored. */
    actionInFlight: null,
    /* True while a /leave request is pending. */
    leaveInFlight: false,
    /* True while a saved seat is being restored after a reload (its first refresh is pending). */
    reconnecting: false,
    /* Current screen (see viewKey); null until the first render. */
    view: null,
    /* Card used by the last play action, so focus can land on its neighbour afterwards. */
    lastPlayedCard: null,
    /* Until then, game socket invalidations wait: the action response already carried the state. */
    suppressWsRefreshUntilMs: 0,
    /* Bot "thinking" notes by player id, and the server time of the event behind each. */
    botThinking: {},
    botThinkingEventAt: {}
};

/** A user action or a leave request is pending. */
export function isBusy() {
    return Boolean(state.actionInFlight || state.leaveInFlight);
}
