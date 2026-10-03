# tnet protocol fixture

`crypt_table.h` is an unmodified copy of `crypt/table.h` from
[nmlpx09/tundriver](https://github.com/nmlpx09/tundriver), revision
`bc4476340af326794b4c58015331b2f9f96393bb`.

Copyright 2026 nlmpx09. Licensed under GPL-2.0; see the project [LICENSE](../../LICENSE).

This fixture lets protocol tests run in a standalone checkout. To compare against
another server version without replacing the fixture, run:

```sh
./tests/run.sh /path/to/tundriver/crypt/table.h
```
