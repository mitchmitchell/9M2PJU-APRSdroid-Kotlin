Test-only TLS fixtures for TakTlsTest. Generated with openssl from a throwaway
"APRSdroid TEST-ONLY CA"; the private keys protect nothing. Password: testpass.
- server.p12: CN=localhost (SAN DNS:localhost), signed by the test CA, + CA chain
- client.p12: client certificate signed by the test CA, + CA chain
- ca.crt: the test CA certificate
