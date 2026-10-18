.PHONY: test rust python lint fmt keryx daemon vectors

test: rust lint python

rust:
	cd core && cargo test --workspace -q

lint:
	cd core && cargo fmt --all --check && cargo clippy --workspace --all-targets -q -- -D warnings

fmt:
	cd core && cargo fmt --all

python: keryx
	cd prototype && python3 -m pytest -q

keryx:
	cd core && cargo build --release -q -p keryx-cli

daemon:
	cd core && cargo build --release -q -p pharos

# Only after an intended wire-format change (see CLAUDE.md).
vectors:
	cd core && KERYX_REGEN=1 cargo test -q -p keryx --test vectors
