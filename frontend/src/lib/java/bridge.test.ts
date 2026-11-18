import { describe, expect, it } from "vitest";
import {
  allowedJavaPath,
  allowedJavaQuery,
  browserSessionCookie,
  javaBaseUrl,
  javaSessionCookie,
  sameWorkspaceOrigin,
} from "./bridge";

const project = "00000000-0000-0000-0000-000000000001";
const task = "11111111-1111-1111-1111-111111111111";
describe("Java bridge boundary", () => {
  it("only forwards an approved type filter on the task history endpoint", () => {
    const tasks = `projects/${project}/research/tasks`;
    expect(allowedJavaQuery("GET", tasks, "?researchType=KNOWLEDGE_QA")).toBe("?researchType=KNOWLEDGE_QA");
    expect(allowedJavaQuery("GET", tasks, "?researchType=ETF_RISK_COMPARISON")).toBe("?researchType=ETF_RISK_COMPARISON");
    for (const [method, path, query] of [
      ["GET", tasks, "?researchType=INVALID"],
      ["GET", tasks, "?researchType=KNOWLEDGE_QA&other=1"],
      ["GET", `${tasks}/${task}`, "?researchType=KNOWLEDGE_QA"],
      ["POST", tasks, "?researchType=KNOWLEDGE_QA"],
    ]) expect(allowedJavaQuery(method, path, query)).toBeNull();
  });
  it("exposes only the project-scoped knowledge endpoints", () => {
    const base = `projects/${project}/knowledge`;
    for (const suffix of ["collections", `collections/${task}`, `releases/${task}`, `releases/${task}/source`, `releases/${task}/documents`, `releases/${task}/publications`, `documents/${task}`, `documents/${task}/pdf`, `documents/${task}/extractions`, `documents/${task}/candidates`, "evidence/jobs", `evidence/jobs/${task}`, "evidence/budget", "model"]) {
      expect(allowedJavaPath("GET", `${base}/${suffix}`.split("/"))).toBe(`${base}/${suffix}`);
    }
    for (const suffix of ["collections", `collections/${task}/cancel`, "documents", `documents/${task}/extractions`, `candidates/${task}/review`, "publications", `evidence/jobs/${task}/cancel`, "model/probe"]) {
      expect(allowedJavaPath("POST", `${base}/${suffix}`.split("/"))).toBe(`${base}/${suffix}`);
    }
    for (const suffix of ["cypher", "admin", `releases/${task}/delete`, "model/secret"]) {
      expect(allowedJavaPath("POST", `${base}/${suffix}`.split("/"))).toBeNull();
    }
    expect(allowedJavaPath("POST", `${base}/query`.split("/"))).toBe(`${base}/query`);
    expect(allowedJavaPath("POST", `${base}/questions`.split("/"))).toBe(`${base}/questions`);
    expect(allowedJavaPath("GET", `${base}/questions`.split("/"))).toBeNull();
    expect(allowedJavaPath("POST", ["internal", "knowledge-agent"])).toBeNull();
    expect(allowedJavaPath("GET", `${base}/query`.split("/"))).toBeNull();
    expect(allowedJavaPath("POST", `${base}/query/admin`.split("/"))).toBeNull();
    expect(allowedJavaPath("POST", `${base}/releases/${task}`.split("/"))).toBeNull();
  });
  it("checks the configured browser origin regardless of Next's internal request hostname", () => {
    expect(
      sameWorkspaceOrigin("http://127.0.0.1:58872", "http://127.0.0.1:58872"),
    ).toBe(true);
    for (const origin of [
      null,
      "http://localhost:58872",
      "http://127.0.0.1:58870",
      "https://untrusted.invalid",
    ]) {
      expect(sameWorkspaceOrigin(origin, "http://127.0.0.1:58872")).toBe(false);
    }
    expect(sameWorkspaceOrigin("http://127.0.0.1:58872", undefined)).toBe(
      false,
    );
  });
  it("allows research CRUD and event replay without opening arbitrary upstream routes", () => {
    const tasks = `projects/${project}/research/tasks`;
    for (const suffix of [
      "",
      `/${task}`,
      `/${task}/result`,
      `/${task}/snapshots`,
      `/${task}/events`,
    ]) {
      expect(allowedJavaPath("GET", (tasks + suffix).split("/"))).toBe(
        tasks + suffix,
      );
    }
    expect(allowedJavaPath("POST", tasks.split("/"))).toBe(tasks);
    expect(
      allowedJavaPath("POST", `${tasks}/${task}/cancel`.split("/")),
    ).toBeTruthy();
    for (const path of [
      "admin/users",
      "projects/not-a-uuid/research/tasks",
      "projects/../me",
      "auth/login/extra",
    ]) {
      expect(allowedJavaPath("GET", path.split("/"))).toBeNull();
    }
    expect(allowedJavaPath("DELETE", tasks.split("/"))).toBeNull();
    expect(
      allowedJavaPath("POST", `${tasks}/${task}/result`.split("/")),
    ).toBeNull();
    expect(allowedJavaPath("GET", ["me%2f.."])).toBeNull();
  });
  it("only permits a fixed loopback Java origin", () => {
    expect(javaBaseUrl("http://127.0.0.1:58870").port).toBe("58870");
    for (const value of [
      "https://example.com",
      "http://user:secret@localhost",
      "http://localhost/private",
      "http://127.0.0.1:58870/?path=/admin",
    ]) {
      expect(() => javaBaseUrl(value)).toThrow();
    }
  });
  it("isolates the Java session from legacy and direct-Java browser cookies", () => {
    expect(
      javaSessionCookie("JSESSIONID=other; signalforge.session_token=legacy"),
    ).toBeNull();
    expect(
      javaSessionCookie("JSESSIONID=other; signalforge-java-session=accepted"),
    ).toBe("JSESSIONID=accepted");
    expect(
      javaSessionCookie("signalforge-java-session=bad\r\nInjected"),
    ).toBeNull();
  });
  it("preserves session rotation/expiry and scopes browser cookies to the bridge", () => {
    expect(
      browserSessionCookie(
        "JSESSIONID=rotated; Path=/; HttpOnly; SameSite=Lax",
      ),
    ).toBe(
      "signalforge-java-session=rotated; Path=/api/java; HttpOnly; SameSite=Strict",
    );
    expect(
      browserSessionCookie("JSESSIONID=; Max-Age=0; Domain=localhost; Path=/"),
    ).toContain("Max-Age=0");
    expect(
      browserSessionCookie("signalforge.session_token=unrelated"),
    ).toBeNull();
  });
});
