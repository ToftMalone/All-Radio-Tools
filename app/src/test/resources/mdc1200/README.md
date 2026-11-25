# MDC-1200 fixtures

- `mdc1200_unit1777_pttid_end_48k.cs16`: a real Motorola radio, unit 0x1777, end-of-transmission PTT ID on
  447.100 MHz, channelized to 48 kHz complex baseband, headerless int16 I/Q (little-endian). Captured by @v2maldo for
  GopherTrunk issue #1220.
- `mdc1200_ref_01_80_1234_48k.s16`: FM discriminator audio of op 0x01, arg 0x80, unit 0x1234, made by the reference
  MDC-1200 modem (Matthew Kaufman's mdc-encode-decode), mono int16 at 48 kHz. Data produced by that program, not its code.

Both from GopherTrunk by Matt Cheramie, `internal/radio/mdc1200/afsk/testdata`, Apache License 2.0
(https://github.com/MattCheramie/GopherTrunk). Used unchanged, as test data only.
