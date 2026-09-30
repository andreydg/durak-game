/*
 * The page's Content-Security-Policy as the backend builds it: `script-src 'self'` plus the hash of
 * the one inline import map. The backend finds that map in the packaged index.html with the regex
 * <script\s+type="importmap"\s*>(.*?)</script> (DOTALL, UTF-8) and hashes the text between the
 * tags; these helpers do exactly the same so tests can check and emulate it.
 */
import {createHash} from "node:crypto";

/* [\s\S] is JavaScript's spelling of Java's DOTALL "." */
export const IMPORT_MAP_PATTERN = /<script\s+type="importmap"\s*>([\s\S]*?)<\/script>/;

export function importMapText(html) {
    const match = IMPORT_MAP_PATTERN.exec(html);
    return match ? match[1] : null;
}

/** 'sha256-<base64>' source expression for the import map, or null when there is none. */
export function importMapHashSource(html) {
    const text = importMapText(html);
    return text == null ? null : `sha256-${createHash("sha256").update(text, "utf8").digest("base64")}`;
}

/** The policy the backend sends for this HTML. */
export function contentSecurityPolicy(html) {
    const hash = importMapHashSource(html);
    return `script-src 'self'${hash ? ` '${hash}'` : ""}; style-src 'self' 'unsafe-inline'`;
}
