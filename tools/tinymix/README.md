# tinymix

Reads and sets the phone's audio mixer controls. simbridge runs it as root to route call audio.

This is the tinymix from [gsm2sip](https://github.com/pulpoff/gsm2sip) (tag v1.4.18, MIT, © pulpoff; see
`LICENSE`), unchanged. It is pure Go with no dependencies, and `scripts/phone.sh build` compiles it for the
phone, so the binary that runs as root is built from the source in this folder.
