export async function researchFetch(path: string, init?: RequestInit) {
  const response = await fetch(`/api/java${path}`, {
    ...init,
    credentials: "same-origin",
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    const error = new Error(
      body.message || body.code || `研究服务返回 ${response.status}`,
    );
    Object.assign(error, { status: response.status });
    throw error;
  }
  return response.status === 204 ? null : response.json();
}

export async function researchCsrf(): Promise<Record<string, string>> {
  const token = await researchFetch("/auth/csrf");
  return { [token.headerName]: token.token };
}

export async function connectResearchSession(password: string) {
  return researchFetch("/auth/login", {
    method: "POST",
    headers: {
      ...(await researchCsrf()),
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: new URLSearchParams({ password }).toString(),
  });
}

export async function disconnectResearchSession() {
  return researchFetch("/auth/logout", {
    method: "POST",
    headers: await researchCsrf(),
  });
}
