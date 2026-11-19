// Brute-force every 24-bit VB6 Rnd() state against the start of a table.
// Mirrors xara-phy rnd.rs (VbRnd) and dat.rs (perm).
//   vbsearch seq  M v0 v1 ...   consecutive draws Int(Rnd*M)
//   vbsearch perm N v0 v1 ...   draw-and-reject permutation of 0..N
// Prints every start state (state before the first advance) that matches.
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MASK 0xFFFFFFu
#define MUL 0xFD43FDu
#define INC 0xC39EC3u

static inline uint32_t adv(uint32_t s) { return (s * MUL + INC) & MASK; }
static inline uint32_t draw(uint32_t *s, uint32_t m) {
    *s = adv(*s);
    float v = (float)*s / 16777216.0f;
    return (uint32_t)(v * (float)m);
}

int main(int argc, char **argv) {
    if (argc < 5) return 2;
    int perm = !strcmp(argv[1], "perm");
    uint32_t m = (uint32_t)atoi(argv[2]);
    int nt = argc - 3;
    uint32_t t[64];
    for (int i = 0; i < nt && i < 64; i++) t[i] = (uint32_t)atoi(argv[3 + i]);
    long hits = 0;
    for (uint32_t s0 = 0; s0 <= MASK; s0++) {
        uint32_t s = s0;
        int ok = 1;
        if (!perm) {
            for (int i = 0; i < nt; i++)
                if (draw(&s, m) != t[i]) { ok = 0; break; }
        } else {
            // accepted values must equal t[]; rejected draws must be earlier values
            int got = 0, guard = 0;
            while (got < nt && guard++ < 4 * nt + 16) {
                uint32_t v = draw(&s, m);
                if (v == t[got]) { got++; continue; }
                int seen = 0;
                for (int j = 0; j < got; j++) if (t[j] == v) { seen = 1; break; }
                if (!seen) { ok = 0; break; }
            }
            if (got < nt) ok = 0;
        }
        if (ok) { printf("match state=0x%06X\n", s0); if (++hits > 20) break; }
    }
    printf("hits=%ld\n", hits);
    return 0;
}
