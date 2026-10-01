/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  env: {
    // The BFF is the only backend the browser talks to. Nothing here knows that
    // qp-marketdata, qp-portfolio or qp-alerts exist.
    NEXT_PUBLIC_API_URL: process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080",
  },
};

export default nextConfig;
