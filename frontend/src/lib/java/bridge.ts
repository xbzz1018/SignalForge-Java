const UUID =
  "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
const TASKS = new RegExp(
  `^projects/${UUID}/research/tasks(?:/${UUID}(?:/(?:result|snapshots|events))?)?$`,
);
const CANCEL = new RegExp(`^projects/${UUID}/research/tasks/${UUID}/cancel$`);
const TASK_COLLECTION = new RegExp(`^projects/${UUID}/research/tasks$`);
const KNOWLEDGE_READ = new RegExp(
  `^projects/${UUID}/knowledge/(?:collections(?:/${UUID})?|releases/${UUID}(?:/(?:source|documents|publications))?|documents/${UUID}(?:/(?:pdf|extractions|candidates))?|evidence/(?:jobs(?:/${UUID})?|budget)|model)$`,
);
const KNOWLEDGE_QUERY = new RegExp(`^projects/${UUID}/knowledge/(?:query|questions)$`);
const KNOWLEDGE_WRITE = new RegExp(
  `^projects/${UUID}/knowledge/(?:collections(?:/${UUID}/cancel)?|documents(?:/${UUID}/extractions)?|candidates/${UUID}/review|publications|evidence/jobs/${UUID}/cancel|model/probe)$`,
);
export const JAVA_COOKIE = "signalforge-java-session";

export function allowedJavaQuery(method: string, path: string, search: string): string | null {
  if (!search) return "";
  if (method !== "GET" || !TASK_COLLECTION.test(path)) return null;
  return /^\?researchType=(ETF_RISK_COMPARISON|KNOWLEDGE_QA)$/.test(search) ? search : null;
}

export function sameWorkspaceOrigin(
  origin: string | null,
  configuredUrl: string | undefined,
): boolean {
  if (!origin || !configuredUrl) return false;
  try {
    return origin === javaBaseUrl(configuredUrl).origin;
  } catch {
    return false;
  }
}

export function allowedJavaPath(
  method: string,
  segments: string[],
): string | null {
  if (segments.some((segment) => !segment || !/^[a-zA-Z0-9_-]+$/.test(segment)))
    return null;
  const path = segments.join("/");
  if (
    method === "GET" &&
    (["auth/csrf", "me", "workspace/config", "health"].includes(path) ||
      (TASKS.test(path) || KNOWLEDGE_READ.test(path)))
  )
    return path;
  if (
    method === "POST" &&
    (["auth/login", "auth/logout"].includes(path) ||
      TASK_COLLECTION.test(path) ||
      (CANCEL.test(path) || KNOWLEDGE_WRITE.test(path) || KNOWLEDGE_QUERY.test(path)))
  )
    return path;
  return null;
}

export function javaBaseUrl(raw: string | undefined): URL {
  const base = new URL(raw || "http://127.0.0.1:58870");
  if (
    base.protocol !== "http:" ||
    !["127.0.0.1", "localhost"].includes(base.hostname) ||
    base.username ||
    base.password ||
    base.pathname !== "/" ||
    base.search ||
    base.hash
  ) {
    throw new Error("Unsupported local Java endpoint");
  }
  return base;
}

export function javaSessionCookie(header: string | null): string | null {
  const value = header
    ?.split(";")
    .map((part) => part.trim())
    .find((part) => part.startsWith(`${JAVA_COOKIE}=`));
  if (!value) return null;
  const token = value.slice(JAVA_COOKIE.length + 1);
  return /^[a-zA-Z0-9._-]{1,256}$/.test(token) ? `JSESSIONID=${token}` : null;
}

export function browserSessionCookie(header: string): string | null {
  const parts = header.split(";").map((part) => part.trim());
  if (!parts[0]?.startsWith("JSESSIONID=")) return null;
  const attributes = parts
    .slice(1)
    .filter((part) => !/^(?:path|domain|httponly|samesite)=?/i.test(part));
  return [
    `${JAVA_COOKIE}=${parts[0].slice("JSESSIONID=".length)}`,
    ...attributes,
    "Path=/api/java",
    "HttpOnly",
    "SameSite=Strict",
  ].join("; ");
}
