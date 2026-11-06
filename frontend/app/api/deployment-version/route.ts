const COMMIT_SHA = /^[0-9a-f]{40}$/;

export function GET(request: Request) {
  // Accessing the incoming Request ensures this value is read at request time,
  // not frozen into a build-time response.
  const requestUrl = new URL(request.url);
  void requestUrl.pathname;

  const configuredSha = process.env.RENDER_GIT_COMMIT ?? "";
  const commit = COMMIT_SHA.test(configuredSha) ? configuredSha : "unknown";

  return Response.json(
    { commit },
    { headers: { "Cache-Control": "no-store" } },
  );
}
