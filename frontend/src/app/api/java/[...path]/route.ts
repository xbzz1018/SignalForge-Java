import { getAuthSession } from "@/lib/auth/access";
import {
  allowedJavaPath,
  allowedJavaQuery,
  browserSessionCookie,
  javaBaseUrl,
  javaSessionCookie,
  sameWorkspaceOrigin,
} from "@/lib/java/bridge";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

async function forward(
  request: Request,
  context: { params: Promise<{ path: string[] }> },
) {
  const session = await getAuthSession(request.headers);
  if (!session)
    return Response.json({ code: "UNAUTHENTICATED" }, { status: 401 });
  // Explicit local development identity mapping. Other legacy users cannot borrow this Java principal.
  const mappedEmail =
    process.env.SIGNALFORGE_AUTH_ADMIN_EMAIL?.trim().toLowerCase();
  if (
    !mappedEmail ||
    session.user.email.toLowerCase() !== mappedEmail ||
    session.user.banned === true
  ) {
    return Response.json(
      { code: "JAVA_IDENTITY_NOT_LINKED", message: "该账号尚未关联研究服务。" },
      { status: 403 },
    );
  }
  const path = allowedJavaPath(request.method, (await context.params).path);
  const query = path ? allowedJavaQuery(request.method, path, new URL(request.url).search) : null;
  if (!path || query === null)
    return Response.json({ code: "UNSUPPORTED_JAVA_ROUTE" }, { status: 404 });
  if (
    request.method === "POST" &&
    !sameWorkspaceOrigin(
      request.headers.get("origin"),
      process.env.BETTER_AUTH_URL,
    )
  ) {
    return Response.json({ code: "INVALID_REQUEST_ORIGIN" }, { status: 403 });
  }
  const headers = new Headers();
  for (const name of [
    "content-type",
    "x-csrf-token",
    "idempotency-key",
    "last-event-id",
    "accept",
  ]) {
    const value = request.headers.get(name);
    if (value) headers.set(name, value);
  }
  const cookie = javaSessionCookie(request.headers.get("cookie"));
  if (cookie) headers.set("cookie", cookie);
  let body: string | undefined;
  if (request.method === "POST") {
    body = await request.text();
    if (new TextEncoder().encode(body).length > 16384)
      return Response.json({ code: "REQUEST_TOO_LARGE" }, { status: 413 });
    if (path === "auth/login") {
      const form = new URLSearchParams(body);
      const username = process.env.SIGNALFORGE_WEB_JAVA_USERNAME;
      if (!username)
        return Response.json(
          { code: "JAVA_IDENTITY_NOT_LINKED" },
          { status: 403 },
        );
      form.set("username", username);
      body = form.toString();
      headers.set("content-type", "application/x-www-form-urlencoded");
    }
  }
  try {
    const base = javaBaseUrl(process.env.SIGNALFORGE_JAVA_URL);
    const response = await fetch(new URL(`api/${path}${query}`, base), {
      method: request.method,
      headers,
      body,
      cache: "no-store",
      redirect: "manual",
      signal: AbortSignal.any([
        request.signal,
        AbortSignal.timeout(path.endsWith("/events") ? 65000 : 15000),
      ]),
    });
    const outgoing = new Headers({
      "Cache-Control": "private, no-store",
      "X-Accel-Buffering": "no",
    });
    if (response.headers.get("content-type"))
      outgoing.set("content-type", response.headers.get("content-type")!);
    if (response.headers.get("content-disposition"))
      outgoing.set("content-disposition", response.headers.get("content-disposition")!);
    outgoing.set("X-Content-Type-Options", "nosniff");
    for (const setCookie of response.headers.getSetCookie()) {
      const translated = browserSessionCookie(setCookie);
      if (translated) outgoing.append("set-cookie", translated);
    }
    if (path === "auth/logout" && response.ok)
      outgoing.append(
        "set-cookie",
        "signalforge-java-session=; Max-Age=0; Path=/api/java; HttpOnly; SameSite=Strict",
      );
    return new Response(response.body, {
      status: response.status,
      headers: outgoing,
    });
  } catch {
    return Response.json(
      {
        code: "RESEARCH_SERVICE_UNAVAILABLE",
        message: "研究服务暂不可用，请确认Java后端已启动。",
      },
      { status: 502 },
    );
  }
}

export const GET = forward;
export const POST = forward;
