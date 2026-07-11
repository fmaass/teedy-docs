window.onload = function () {
  // Vendored Swagger UI (swagger-ui-dist), served entirely from /apidoc — no CDN,
  // no external requests. The spec is loaded relative to this page so it resolves
  // under any context path (root in production, /docs-web under the dev Jetty).
  window.ui = SwaggerUIBundle({
    url: './openapi.json',
    dom_id: '#swagger-ui',
    deepLinking: true,
    presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
    plugins: [SwaggerUIBundle.plugins.DownloadUrl],
    layout: 'StandaloneLayout',
    // Never contact the online swagger.io validator badge — strict no-external-request posture.
    validatorUrl: null,
    // Send the session cookie on "Try it out" requests to same-origin /api.
    requestInterceptor: function (req) {
      req.credentials = 'same-origin';
      return req;
    },
    oauth2RedirectUrl: window.location.origin + window.location.pathname.replace(/index\.html$/, '') + 'oauth2-redirect.html',
  });
};
