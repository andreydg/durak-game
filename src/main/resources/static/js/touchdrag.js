/*
 * Touch and pen dragging for hand cards on phones and tablets. A mouse keeps HTML5 drag and drop
 * (main.js); a finger gets the same gesture through pointer events: press a card and slide it up
 * to the table. The drop is decided exactly as a desktop drop, and the same outline previews it.
 *
 * Gestures on a card: a slide that is mostly up or down drags it; a sideways swipe still scrolls
 * a hand wider than its rail (app.css gives cards `touch-action: pan-x pinch-zoom` once <html>
 * has .touch-drag), while on a hand that fits any slide drags; a tap still selects. Nothing here
 * depends on new markup, so a page from an earlier release that loads this module drags the same
 * way, and a stale stylesheet only costs the drag, not the tap.
 */
import {dragEdgeScroll, touchDragIntent} from "./logic.js";

/* A drag ends with the finger lifted on the card's button; never let that become a select click. */
const CLICK_SUPPRESS_MS = 600;
/* The card rides above the fingertip, so the finger never hides it; the finger marks the drop. */
const LIFT = 0.85;

/**
 * Wires touch dragging onto `hand`.
 *   canDrag(button)          whether this card may be dragged now
 *   targetAt(x, y)           the drop target under a viewport point, or null away from the table
 *   preview(code, target)    outline what dropping there would do (null clears the outline)
 *   drop(code, target)       play the card there, as a desktop drop would
 *   visibleBand()            {top, bottom} of the viewport to keep the drag inside; a finger held
 *                            near either edge scrolls the page (bottom: Infinity never scrolls down)
 */
export function installTouchDrag({hand, canDrag, targetAt, preview, drop, visibleBand}) {
    document.documentElement.classList.add("touch-drag");
    let gesture = null;
    let suppressClickUntil = 0;

    hand.addEventListener("pointerdown", (e) => {
        // A real tap starts with a press; a browser's stray click after a drag never does.
        suppressClickUntil = 0;
        if (e.pointerType === "mouse" || !e.isPrimary || e.button !== 0 || gesture) return;
        const button = e.target.closest?.(".hand-card-btn");
        if (!button || !hand.contains(button) || !canDrag(button)) return;
        gesture = {
            pointerId: e.pointerId, button, code: button.dataset.cardCode,
            startX: e.clientX, startY: e.clientY, x: e.clientX, y: e.clientY,
            dragging: false, ghost: null, lift: 0, frame: 0, target: null
        };
        window.addEventListener("pointermove", onMove);
        window.addEventListener("pointerup", onUp);
        window.addEventListener("pointercancel", onCancel);
        // A second finger (a pinch) ends the gesture rather than dragging two cards.
        window.addEventListener("pointerdown", onOtherPointer, true);
    });

    // Long-pressing a card must not open the image menu (Android) while it is being dragged.
    hand.addEventListener("contextmenu", (e) => {
        if (gesture) e.preventDefault();
    });

    // The button under a lifted finger may still receive a click: a drag is not a selection.
    hand.addEventListener("click", (e) => {
        if (performance.now() >= suppressClickUntil) return;
        suppressClickUntil = 0;
        e.preventDefault();
        e.stopPropagation();
    }, true);

    document.addEventListener("visibilitychange", () => {
        if (document.visibilityState === "hidden") onCancel();
    });

    function onMove(e) {
        if (!gesture || e.pointerId !== gesture.pointerId) return;
        gesture.x = e.clientX;
        gesture.y = e.clientY;
        if (!gesture.dragging) {
            const intent = touchDragIntent(e.clientX - gesture.startX, e.clientY - gesture.startY,
                undefined, scrollsSideways(hand));
            if (intent === "wait") return;
            // Sideways: the browser scrolls the hand. A card that became undraggable stays put.
            if (intent === "swipe" || !gesture.button.isConnected || !canDrag(gesture.button)) {
                end(false);
                return;
            }
            begin();
        }
        schedule();
    }

    /** Whether a sideways swipe from `node` would scroll something (a hand wider than its rail). */
    function scrollsSideways(node) {
        for (let n = node; n && n !== document.body; n = n.parentElement) {
            const overflow = getComputedStyle(n).overflowX;
            if ((overflow === "auto" || overflow === "scroll") && n.scrollWidth > n.clientWidth + 1) return true;
        }
        return false;
    }

    function onUp(e) {
        if (!gesture || e.pointerId !== gesture.pointerId) return;
        if (!gesture.dragging) {
            end(false); // a tap: the click that follows selects the card as before
            return;
        }
        const {code, button} = gesture;
        const target = targetAt(e.clientX, e.clientY);
        end(true);
        // A card a refresh took out of the hand mid-drag is not played.
        if (target && button.isConnected) drop(code, target);
    }

    function onCancel() {
        if (gesture) end(gesture.dragging);
    }

    function onOtherPointer(e) {
        if (gesture && e.pointerId !== gesture.pointerId) onCancel();
    }

    function begin() {
        const {button} = gesture;
        gesture.dragging = true;
        try {
            button.setPointerCapture(gesture.pointerId);
        } catch {
            // Already released by the browser; moves still reach the window listeners.
        }
        button.classList.add("dragging");
        document.documentElement.classList.add("touch-dragging");
        gesture.ghost = createGhost(button);
        place();
    }

    /** Keeps the ghost centred above the fingertip. */
    function place() {
        const {x, y, ghost} = gesture;
        ghost.style.transform = `translate3d(${Math.round(x - ghost.offsetWidth / 2)}px, ${Math.round(y - gesture.lift)}px, 0)`;
    }

    function createGhost(button) {
        const face = button.querySelector("img");
        const rect = (face || button).getBoundingClientRect();
        const ghost = document.createElement("div");
        ghost.className = "touch-drag-ghost";
        ghost.setAttribute("aria-hidden", "true");
        // The essentials are inline so the ghost still works with a stylesheet that lacks its rule.
        Object.assign(ghost.style, {
            position: "fixed", left: "0", top: "0", zIndex: "1000", pointerEvents: "none",
            width: `${Math.round(rect.width)}px`
        });
        if (face) {
            const img = face.cloneNode(false);
            img.alt = "";
            img.removeAttribute("class");
            Object.assign(img.style, {display: "block", width: "100%", height: "auto"});
            ghost.append(img);
        }
        gesture.lift = rect.height * LIFT;
        document.body.append(ghost);
        return ghost;
    }

    function schedule() {
        if (gesture?.dragging && !gesture.frame) gesture.frame = requestAnimationFrame(frame);
    }

    function frame() {
        if (!gesture?.dragging) return;
        gesture.frame = 0;
        const {x, y} = gesture;
        place();
        // Held near a sticky bar, the page scrolls as a desktop drag does near the window edge.
        const band = visibleBand();
        const step = dragEdgeScroll(y, band.top, band.bottom);
        const before = window.scrollY;
        if (step) window.scrollBy(0, step);
        gesture.target = targetAt(x, y);
        preview(gesture.code, gesture.target);
        if (step && window.scrollY !== before) schedule();
    }

    /** Ends the gesture; `dragged` tears down the drag's ghost, outline and click. */
    function end(dragged) {
        const current = gesture;
        gesture = null;
        window.removeEventListener("pointermove", onMove);
        window.removeEventListener("pointerup", onUp);
        window.removeEventListener("pointercancel", onCancel);
        window.removeEventListener("pointerdown", onOtherPointer, true);
        if (!current || !dragged) return;
        if (current.frame) cancelAnimationFrame(current.frame);
        current.ghost?.remove();
        current.button.classList.remove("dragging");
        document.documentElement.classList.remove("touch-dragging");
        preview(current.code, null);
        suppressClickUntil = performance.now() + CLICK_SUPPRESS_MS;
    }
}
