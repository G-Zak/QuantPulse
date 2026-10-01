"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { clearToken, getToken } from "@/lib/api";

const LINKS = [
  { href: "/", label: "Market" },
  { href: "/analysis", label: "Analysis" },
  { href: "/income", label: "Income" },
  { href: "/portfolio", label: "Portfolio" },
  { href: "/alerts", label: "Alerts" },
];

export function Nav() {
  const pathname = usePathname();
  const [authed, setAuthed] = useState(false);

  // localStorage doesn't exist during SSR, so check after mount (avoids a hydration mismatch).
  useEffect(() => {
    setAuthed(!!getToken());
    const onStorage = () => setAuthed(!!getToken());
    window.addEventListener("storage", onStorage);
    return () => window.removeEventListener("storage", onStorage);
  }, [pathname]);

  return (
    <nav className="nav">
      <span className="brand">QUANTPULSE</span>
      {LINKS.map((l) => (
        <Link key={l.href} href={l.href}
              className={pathname === l.href ? "active" : ""}>
          {l.label}
        </Link>
      ))}
      <span className="spacer" />
      {authed ? (
        <button onClick={() => { clearToken(); setAuthed(false); location.reload(); }}>
          Sign out
        </button>
      ) : (
        <Link href="/portfolio" className="muted">Sign in</Link>
      )}
    </nav>
  );
}
