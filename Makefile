.PHONY: test rust python lint fmt nvl daemon vectors

test: rust lint python

rust:
	cd core && cargo test --workspace -q

lint:
	cd core && cargo fmt --all --check && cargo clippy --workspace --all-targets -q -- -D warnings

fmt:
	cd core && cargo fmt --all

python: nvl
	cd prototype && python3 -m pytest -q

nvl:
	cd core && cargo build --release -q -p nvl-cli

daemon:
	cd core && cargo build --release -q -p nvl-daemon

# Only after an intended wire-format change (see CLAUDE.md).
vectors:
	cd core && NVL_REGEN=1 cargo test -q -p nvl-proto --test vectors
