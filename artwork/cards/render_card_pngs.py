#!/usr/bin/env python3
"""Rasterise the generated SVG card faces into the density buckets the app uses.

This is the missing half of `generate_card_svgs.py`: the SVGs are the source, and
this turns them into the `res/drawable-*/card_*.png` files that
`GameTable.initCards()` actually decodes.

    python3 artwork/cards/render_card_pngs.py            # write to artwork/cards/png
    python3 artwork/cards/render_card_pngs.py --install  # ...and copy into res/

**It writes to `artwork/cards/png/` and not into `res/` by default.** The art
already in `res/` is the shipped set, hand-drawn years ago, and overwriting it is
not something to do by accident or by default. `--install` is the deliberate
version, and it is the only thing here that writes there.

## Supersampling

Rendering straight to 52x80 gives noticeably worse glyphs than the originals,
because the labels are small text and a text rasteriser asked for one size will
hit it exactly. Each face is rendered at 4x and downsampled with Lanczos first.
The cost is nothing at this size and the difference is visible on the corner
numerals.

## Quantisation

The shipped set went through `pngquant 128`, which is what took the APK from 13 MB
to 6 MB; issue #9 records that 64 colours was tried and looked too bad. `pngquant`
is not installed here, so this uses ImageMagick's PNG8 output at the same 256-entry
palette depth. That is close but not identical -- `pngquant` uses a better
perceptual quantiser -- so treat the sizes below as indicative and install
`pngquant` before judging the APK budget.
"""
import glob
import os
import re
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
RES = os.path.join(ROOT, 'com.smccloud.hotdeath', 'app', 'src', 'main', 'res')
SVG = os.path.join(HERE, 'svg')
PNG = os.path.join(HERE, 'png')

# The five buckets that carry card art, at the exact pixel sizes the shipped set
# uses. `drawable-ldpi` holds only the two launcher icons and gets nothing.
#
# Note these are three geometries across five buckets, and they are not one aspect
# ratio -- 52x80 is 0.650, 77x120 is 0.6417, 103x160 is 0.6438. That is the whole
# reason the SVG source is worth having: the vector is rendered at each bucket's
# own size instead of being resampled from a master.
BUCKETS = [
    ('drawable-mdpi',        52,  80),
    ('drawable-hdpi',        77, 120),
    ('drawable-xlarge-mdpi', 77, 120),
    ('drawable-xhdpi',      103, 160),
    ('drawable-xlarge-hdpi', 103, 160),
]

COLOURS = 128
SUPERSAMPLE = 4


def sh(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError('%s failed:\n%s' % (' '.join(cmd), r.stderr.strip()))
    return r


def render(svg, width, height, dest):
    """4x render, Lanczos downsample, quantise to a palette PNG."""
    big = '%dx%d' % (width * SUPERSAMPLE, height * SUPERSAMPLE)
    tmp = dest + '.big.png'
    sh(['rsvg-convert', '-w', str(width * SUPERSAMPLE), '-h', str(height * SUPERSAMPLE), svg, '-o', tmp])
    sh(['convert', tmp, '-filter', 'Lanczos', '-resize', '%dx%d!' % (width, height),
        '-colors', str(COLOURS), '-depth', '8', 'PNG8:' + dest])
    os.remove(tmp)


def main(argv):
    install = '--install' in argv
    faces = sorted(os.path.basename(p)[:-4] for p in glob.glob(os.path.join(SVG, '*.svg')))
    if not faces:
        raise SystemExit('no SVGs in %s -- run generate_card_svgs.py first' % SVG)

    print('%d faces x %d buckets, %d colours, %dx supersampled'
          % (len(faces), len(BUCKETS), COLOURS, SUPERSAMPLE))

    written = []
    for bucket, w, h in BUCKETS:
        outdir = os.path.join(PNG, bucket)
        os.makedirs(outdir, exist_ok=True)
        total = 0
        for name in faces:
            dest = os.path.join(outdir, name + '.png')
            render(os.path.join(SVG, name + '.svg'), w, h, dest)
            total += os.path.getsize(dest)
        written.append((bucket, w, h, len(faces), total))
        print('  %-22s %3dx%-3d  %2d files  %5d KB' % (bucket, w, h, len(faces), total // 1024))

    if install:
        for bucket, _, _, _, _ in written:
            src = os.path.join(PNG, bucket)
            dst = os.path.join(RES, bucket)
            for f in sorted(glob.glob(os.path.join(src, '*.png'))):
                shutil.copyfile(f, os.path.join(dst, os.path.basename(f)))
            print('  installed into %s' % dst)
    else:
        print('\nnot installed. re-run with --install to copy into res/drawable-*.')

    # Compare against the art that is already shipped, because size is the whole
    # constraint on this set.
    print('\nagainst the shipped set (card_*.png only):')
    print('  %-22s %10s %10s %8s' % ('bucket', 'shipped', 'generated', 'delta'))
    for bucket, w, h, n, total in written:
        old = 0
        for f in glob.glob(os.path.join(RES, bucket, 'card_*.png')):
            old += os.path.getsize(f)
        # The shipped count includes card_badge, which this set does not cover.
        delta = (total - old) / float(old) * 100.0
        print('  %-22s %8d KB %8d KB %+7.1f%%' % (bucket, old // 1024, total // 1024, delta))


if __name__ == '__main__':
    main(sys.argv[1:])