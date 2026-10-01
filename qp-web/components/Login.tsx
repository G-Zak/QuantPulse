"use client";

import { useState } from "react";
import { api, setToken } from "@/lib/api";

export function Login({ onSuccess }: { onSuccess: () => void }) {
  const [username, setUsername] = useState("zakaria");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const res = await api.login(username, password);
      setToken(res.token);
      onSuccess();
    } catch {
      // Same message as the server, so nobody can tell which usernames exist.
      setError("Invalid credentials");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card" style={{ maxWidth: 380, margin: "60px auto" }}>
      <h3>Sign in</h3>
      <form onSubmit={submit} style={{ display: "grid", gap: 10, marginTop: 12 }}>
        <input value={username} onChange={(e) => setUsername(e.target.value)}
               placeholder="Username" autoComplete="username" />
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)}
               placeholder="Password" autoComplete="current-password" />
        <button className="primary" disabled={busy || !password}>
          {busy ? "Signing in…" : "Sign in"}
        </button>
        {error && <div className="error">{error}</div>}
      </form>
    </div>
  );
}
