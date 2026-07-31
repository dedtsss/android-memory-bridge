# Security policy

Please report vulnerabilities privately to the repository owner. Do not include a live API token, private memory content, or a production Gateway URL in a public issue.

## Sensitive data handling

- The user supplies a bearer token at runtime; it is never committed to this repository.
- The token is encrypted with a non-exportable Android Keystore AES-GCM key before persistence.
- The application has no analytics, crash-reporting SDK, clipboard listener, or production content logging.
- Gateway request/response content is held only long enough to perform the user-initiated operation.
- Release networking requires HTTPS. Certificate pinning is intentionally not in this MVP because no Gateway PKI infrastructure has been specified.

If Android Keystore decryption fails (for example after key invalidation), the encrypted token is discarded and must be entered again.
