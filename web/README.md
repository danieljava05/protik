# ProTik web (test harness)

A thin Next.js + TypeScript client whose only job is to exercise the backend and make its
behaviour visible: status codes, correlation IDs, idempotent replays and RFC 9457 problem
documents. The backend is the product; this is deliberately not polished.

```bash
npm install
npm run dev     # http://localhost:3000
npm run lint
npm run build
```

It talks to `NEXT_PUBLIC_API_URL` (default `http://localhost:8080`). The backend allows the
`http://localhost:3000` origin by default (`CORS_ALLOWED_ORIGINS` changes it). Start the backend
with `docker compose up -d` and `./mvnw spring-boot:run` in the repository root.

The access token is kept in memory only, so it is gone on reload. That is intentional for now;
the final session design arrives with refresh tokens (roadmap T1.2).
