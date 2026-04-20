#!/usr/bin/env bash
#
# Preprocess ScalarEvolution OBJ meshes from the upstream 1.7.10 source tree
# into block-space coordinates so the neoforge:obj loader renders them in
# the right place, AND inject a material binding so the loader actually emits
# the geometry.
#
# ## Coordinate-space transform
#
# Upstream OBJ vertices are centered at origin in [-0.5, 0.5]. Minecraft block
# space is [0, 1] with origin at the corner.
#
# - "Fat" models (workstation/powermark/keyboard/keyboard_mouse):
#     v -> v + (0.5, 0.5, 0.5)          — whole block filled
# - "Thin" models (tinkerpad/vt100/crt_monitor):
#     v -> v*0.75 + (0.5, 0.375, 0.5)   — smaller, sitting on the floor
#
# ## Material binding
#
# NeoForge's `ObjModel.ModelMesh.addQuads` returns early if `mat == null`
# (see `ObjModel.java` line 541-543). That means every face without a
# `usemtl` scope is silently dropped at bake time — blocks render as
# invisible geometry with particles still working.
#
# To fix: drop the upstream `mtllib`/`usemtl` lines (they reference
# Blockbench-generated `m_<uuid>` materials that aren't shipped) and inject
# a reference to our own `default.mtl` with a single `main` material whose
# `map_Kd` points at the `#texture` slot in the JSON model.
#
# We emit:
#   mtllib default.mtl    (right before the first `o`/`v`/`g` line)
#   usemtl main           (right before the first `f` face declaration)
#
# The `#texture` slot is bound per-block by `ScevBlockStateProvider`.
#
# Usage: ./scripts/preprocess-obj.sh <upstream-source-dir>
# Default upstream-source-dir: ~/ScalarEvolution/src/main/resources/assets/scev/models

set -euo pipefail

UPSTREAM="${1:-$HOME/ScalarEvolution/src/main/resources/assets/scev/models}"
OUT="$(cd "$(dirname "$0")/.." && pwd)/src/main/resources/assets/scev/models/block"

if [[ ! -d "$UPSTREAM" ]]; then
    echo "Error: upstream source dir not found: $UPSTREAM" >&2
    echo "Usage: $0 <upstream-source-dir>" >&2
    exit 1
fi

mkdir -p "$OUT"

AWK_PROGRAM='
BEGIN {
    scale = (fat == "1") ? 1.0 : 0.75
    offx = 0.5
    offy = (fat == "1") ? 0.5 : 0.375
    offz = 0.5
    inserted_mtllib = 0
    inserted_usemtl = 0
}
# Drop the upstream material references — they point at missing files.
/^mtllib / { next }
/^usemtl / { next }
# Rescale vertices into block space.
/^v / {
    # Insert our mtllib before the first geometry line (typically `o cube`,
    # which comes before any `v`).
    if (!inserted_mtllib) {
        print "mtllib default.mtl"
        inserted_mtllib = 1
    }
    printf "v %.6f %.6f %.6f\n", $2*scale + offx, $3*scale + offy, $4*scale + offz
    next
}
# Also insert mtllib on first `o`/`g` if no `v` preceded it.
/^o / || /^g / {
    if (!inserted_mtllib) {
        print "mtllib default.mtl"
        inserted_mtllib = 1
    }
    print
    next
}
# Insert `usemtl main` immediately before the first face.
/^f / {
    if (!inserted_usemtl) {
        print "usemtl main"
        inserted_usemtl = 1
    }
    print
    next
}
{ print }
END {
    # Belt-and-braces: if the file somehow had no `o`/`g`/`v`/`f` lines (empty
    # mesh), we at least emit the mtllib so the JSON model still resolves.
    if (!inserted_mtllib) {
        print "mtllib default.mtl"
    }
}
'

for name in workstation powermark keyboard keyboard_mouse; do
    awk -v fat=1 "$AWK_PROGRAM" "$UPSTREAM/${name}.obj" > "$OUT/${name}.obj"
    echo "fat  -> $OUT/${name}.obj"
done

for name in tinkerpad vt100 crt_monitor; do
    awk -v fat=0 "$AWK_PROGRAM" "$UPSTREAM/${name}.obj" > "$OUT/${name}.obj"
    echo "thin -> $OUT/${name}.obj"
done

echo
echo "Done. Run ./gradlew runData to regenerate model JSON wrappers."
