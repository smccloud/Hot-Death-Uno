#!/usr/bin/env python3
"""Generate SVG card faces and the card back from the card table.

Everything geometric and chromatic here was **measured** out of the existing
bitmaps in `res/drawable-xlarge-hdpi` rather than guessed. See README.md beside
this file for what that does and does not buy you.

The card table itself is parsed out of the Kotlin, so the set of faces cannot
drift from the app: `Card.kt` supplies the ID constants, and
`GameTable.initCards()` supplies the ID-to-drawable mapping including the three
cards that carry a family-friendly spelling as well as an original one.

Usage:  python3 artwork/cards/generate_card_svgs.py
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
APP = os.path.join(ROOT, 'com.smccloud.hotdeath')
OUT = os.path.join(HERE, 'svg')

# --------------------------------------------------------------------------
# Measured geometry and palette
# --------------------------------------------------------------------------
# Card is 103x160 (drawable-xlarge-hdpi). The pale panel was located by taking
# the largest pale connected component on each card and it lands on the same box
# every time: x 17..82, y 31..126. Corner radii are read off the rounded frame
# and are approximate -- at this size the antialiased edge is 2px wide, so
# anything within a pixel of these is a guess dressed as a measurement.
W, H = 103, 160
PANEL = (17, 31, 66, 96)
CARD_R = 7
PANEL_R = 6

# Suit colours are the mean of the frame interior over a clean patch (x 3..15,
# y 60..110), which sidesteps the antialiased card edge and the corner glyphs.
# Each was consistent to within a quantisation step across every card sampled.
PANEL_BONE = '#DCDBD6'
SUIT = {
    'RED':    '#C70E26',
    'GREEN':  '#028139',
    'BLUE':   '#044FA1',
    'YELLOW': '#E6880B',
}
# Wilds and the back are dark, not a suit colour. Sampled from the modal
# background of card_back.png and card_wild_drawfour.png.
DARK = '#262529'
GOLD = '#FFC800'

FONT = "'DejaVu Sans', Verdana, Geneva, sans-serif"
FONT_DISPLAY = "'DejaVu Sans', Verdana, Geneva, sans-serif"

# Corner glyph positions, as fractions of the card. The top-left glyph is
# upright; the bottom-right one is the same glyph rotated 180 degrees, which is
# what every face in the set does.
CORNER_TL = (0.105, 0.115)
CORNER_BR = (0.895, 0.885)


def esc(text):
    return (text.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;'))


def txt(x, y, s, size, fill, anchor='middle', weight='bold', family=FONT,
        transform='', extra=''):
    t = ' transform="%s"' % transform if transform else ''
    return ('  <text x="%.2f" y="%.2f" font-family="%s" font-size="%s" '
            'font-weight="%s" fill="%s" text-anchor="%s"%s%s>%s</text>\n'
            % (x, y, family, size, weight, fill, anchor, t, extra, esc(s)))


def corner_glyphs(glyph, fill, size=13):
    """Top-left upright, bottom-right rotated 180."""
    out = []
    out.append(txt(CORNER_TL[0] * W, CORNER_TL[1] * H, glyph, size, fill,
                   anchor='middle'))
    out.append(txt(CORNER_BR[0] * W, CORNER_BR[1] * H, glyph, size, fill,
                   anchor='middle',
                   transform='rotate(180 %.2f %.2f)' % (CORNER_BR[0] * W, CORNER_BR[1] * H)))
    return out


def frame(body, fill, radius=CARD_R):
    return ('<rect x="0.5" y="0.5" width="%d" height="%d" rx="%s" fill="%s"/>\n'
            % (W - 1, H - 1, radius, fill))


def panel():
    x, y, w, h = PANEL
    return ('<rect x="%d" y="%d" width="%d" height="%d" rx="%s" fill="%s"/>\n'
            % (x, y, w, h, PANEL_R, PANEL_BONE))


WORD_Y = {'top': 55, 'bottom': 112, 'mid': 84, 'low': 100}


def outlined(x, y, s, size, fill, stroke='#1A1A1A', width=2.2):
    """Pale text with a dark outline, drawn in two passes.

    `paint-order="stroke"` is the obvious way to do this and it does not work:
    librsvg has never implemented it, so the stroke goes on *after* the fill and
    every label came out hollow -- an outlined letter with no colour inside it.
    Rendering the stroke first as a fattened copy and then the fill on top is
    renderer-independent, at the cost of one extra element per label.
    """
    halo = txt(x, y, s, size, stroke, weight='bold',
               extra=' stroke="%s" stroke-width="%s" stroke-linejoin="round"'
                     % (stroke, width))
    return halo + txt(x, y, s, size, fill, weight='bold')


# --------------------------------------------------------------------------
# Motifs. Each is a rough geometric stand-in, NOT a trace of the original.
# --------------------------------------------------------------------------
def motif_arrows(colour, cy, n=4, r=17):
    """Outward arrows behind the glyph.

    Count and colour are per card, because the originals disagree: the Spreader
    has four red arrows, the Double Skip two gold ones and the Reverse Skip two
    green ones. A single suit-coloured set of four was wrong on all three.
    """
    import math
    out = []
    cx = W / 2.0
    # Two arrows sit on the horizontal, four on the diagonals.
    start = 0.0 if n == 2 else 45.0
    for i in range(n):
        a = math.radians(start + i * (360.0 / n))
        ux, uy = math.cos(a), math.sin(a)
        px, py = -uy, ux
        bx, by = cx + r * ux, cy + r * uy          # tip
        sx, sy = cx + (r - 9) * ux, cy + (r - 9) * uy
        out.append(
            '  <path d="M %.2f %.2f L %.2f %.2f L %.2f %.2f L %.2f %.2f Z" fill="%s"/>\n'
            % (bx, by,
               sx + 4.2 * px, sy + 4.2 * py,
               sx - 4.2 * px, sy - 4.2 * py,
               sx, sy, colour))
    return out


def motif_crown(gold, y):
    """The Holy Defender's crown: three points over a band.

    The first attempt was two flat triangles, which read as a paper hat rather
    than a crown at card size.
    """
    cx = W / 2.0
    half, base = 20.0, 11.0
    body = ('  <path d="M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z"'
            ' fill="%s"/>\n'
            % (cx - half, y + base, cx - half, y + 2, cx - 10, y + 7,
               cx, y - 3, cx + 10, y + 7, gold))
    body += ('  <rect x="%.1f" y="%.1f" width="%.1f" height="3.4" rx="1.2"'
             ' fill="%s"/>\n' % (cx - half, y + base, half * 2, gold))
    return body


def motif_target(r, cy):
    """The MAD card's bullseye: dark disc inside a thin pale ring."""
    cx = W / 2.0
    return ('  <circle cx="%.1f" cy="%.1f" r="%.1f" fill="#141414"/>\n'
            '  <circle cx="%.1f" cy="%.1f" r="%.1f" fill="none" stroke="#F2EFE8"'
            ' stroke-width="1.3"/>\n' % (cx, cy, r, cx, cy, r))


def motif_pinwheel(cy, r=20):
    """The wild's four-colour wheel."""
    import math
    cx = W / 2.0
    out = []
    order = ['RED', 'GREEN', 'BLUE', 'YELLOW']
    for i, name in enumerate(order):
        a0 = math.radians(-90 + i * 90)
        a1 = a0 + math.radians(90)
        x0, y0 = cx + r * math.cos(a0), cy + r * math.sin(a0)
        x1, y1 = cx + r * math.cos(a1), cy + r * math.sin(a1)
        out.append('  <path d="M %.2f %.2f L %.2f %.2f A %.1f %.1f 0 0 1 %.2f %.2f Z"'
                   ' fill="%s"/>\n' % (cx, cy, x0, y0, r, r, x1, y1, SUIT[name]))
    return out


# --------------------------------------------------------------------------
# Per-card art direction
# --------------------------------------------------------------------------
# Numbers and the plain D/S/R are derived. Everything else needs a decision that
# the ID does not carry, so it is written out: the word on the face, the corner
# glyph, and the motif.
#
# glyph   -- the big symbol in the middle of the panel
# words   -- pale text over the panel, (top, bottom)
# motif   -- name of a motif function
# corners -- the small corner glyph; defaults to glyph
FACE = {
    # -- action variants: letter plus a word and arrows
    'card_red_d_spreader':    dict(glyph='D', words=('Spreader', None), motif='arrows'),
    'card_green_d_spreader':  dict(glyph='D', words=('Spreader', None), motif='arrows'),
    'card_blue_d_spreader':   dict(glyph='D', words=('Spreader', None), motif='arrows'),
    'card_yellow_d_spreader': dict(glyph='D', words=('Spreader', None), motif='arrows'),
    'card_red_s_double':      dict(glyph='S', words=('Double', 'Skip'), motif='arrows',
                                   arrows=2, arrow_colour='#E6880B'),
    'card_green_s_double':    dict(glyph='S', words=('Double', 'Skip'), motif='arrows',
                                   arrows=2, arrow_colour='#E6880B'),
    'card_blue_s_double':     dict(glyph='S', words=('Double', 'Skip'), motif='arrows',
                                   arrows=2, arrow_colour='#E6880B'),
    'card_yellow_s_double':   dict(glyph='S', words=('Double', 'Skip'), motif='arrows',
                                   arrows=2, arrow_colour='#E6880B'),
    'card_red_r_skip':        dict(glyph='R', words=('Reverse', 'Skip'), motif='arrows',
                                   arrows=2, arrow_colour='#028139'),
    'card_green_r_skip':      dict(glyph='R', words=('Reverse', 'Skip'), motif='arrows',
                                   arrows=2, arrow_colour='#028139'),
    'card_blue_r_skip':       dict(glyph='R', words=('Reverse', 'Skip'), motif='arrows',
                                   arrows=2, arrow_colour='#028139'),
    'card_yellow_r_skip':     dict(glyph='R', words=('Reverse', 'Skip'), motif='arrows',
                                   arrows=2, arrow_colour='#028139'),

    # -- wilds. The centre wheel with "Wild" over it; the corner word names the
    #    variant, which is what the original faces do.
    'card_wild':             dict(glyph=None, corners='Wild', motif='pinwheel', wildword='Wild'),
    'card_wild_drawfour':    dict(glyph=None, corners='Draw 4', motif='pinwheel', wildword='Wild'),
    'card_wild_mystery':     dict(glyph=None, corners='Mystery', motif='pinwheel', wildword='Wild'),
    'card_wild_hd':          dict(glyph=None, corners='Hot Death', motif='pinwheel', wildword='Wild'),
    'card_wild_db':          dict(glyph=None, corners='Delayed', motif='pinwheel', wildword='Wild'),
    'card_wild_hos':         dict(glyph=None, corners='Harvester', motif='pinwheel', wildword='Wild'),

    # -- the bastard cards and the specials. Word on the face, and the short
    #    symbol repeated in the corners.
    'card_red_0_hd':       dict(glyph='O', corners='0', words=('Holy', 'Defender'),
                               motif='crown', glyph_fill=GOLD, glyph_size=34),
    'card_red_2_glasnost': dict(glyph=None, corners='2', words=('Glasnost', None)),
    'card_red_5_magic':    dict(glyph='5', corners='5'),
    'card_green_0_quitter': dict(glyph='0', corners='0', words=('Quitter', None)),
    'card_green_3_aids':   dict(glyph='3', corners='3', words=('AIDS', 'Virus')),
    'card_green_3_aids_ff': dict(glyph='3', corners='3', words=('Virus', None)),
    'card_green_4_irish':  dict(glyph='4', corners='4', words=('Irish', None)),
    'card_blue_0_fuckyou': dict(glyph='0', corners='0', words=('Fuck You', None)),
    'card_blue_0_fuckyou_ff': dict(glyph='0', corners='0', words=('Retaliation', None)),
    'card_blue_2_shield':  dict(glyph='2', corners='2', words=('Shield', None)),
    'card_yellow_0_shitter':   dict(glyph='0', corners='0', words=('Shitter', 'Big Bro')),
    'card_yellow_0_shitter_ff': dict(glyph='0', corners='0', words=('Big Brother', None)),
    'card_yellow_1_mad':   dict(glyph='1', corners='1', words=(None, 'M·A·D'),
                               motif='target', word_slot='bottom',
                               word_fill='#C70E26'),
    # The deck gives the 69 a value of 6, and the existing bitmap duly shows a 6.
    # That is an artefact of the original art, not the card's identity, so this
    # says 69. See README.md.
    'card_yellow_69':      dict(glyph='69', corners='9'),
}


# --------------------------------------------------------------------------
# Reading the card table out of the Kotlin
# --------------------------------------------------------------------------
def read_table():
    card = open(os.path.join(APP, 'app/src/main/java/com/smccloud/hotdeath/Card.kt')).read()
    gt = open(os.path.join(APP, 'app/src/main/java/com/smccloud/hotdeath/GameTable.kt')).read()

    declared = set(re.findall(r'const val (ID_\w+)\s*=', card))
    block = gt[gt.index('fun initCards'):]
    block = block[:block.index('\n\tprivate fun ')]

    pairs = []
    ff = False
    for line in block.split('\n'):
        m = re.search(r'm_imageIDLookup\.put\s*\(\s*Card\.(ID_\w+)\s*,\s*R\.drawable\.(\w+)\s*\)', line)
        if m:
            pairs.append((m.group(1), m.group(2)))
        if 'getFamilyFriendly()' in line:
            ff = True
        elif line.strip() == '}' and ff:
            ff = False

    mapped = set(i for i, _ in pairs)
    missing = declared - mapped
    if missing:
        raise SystemExit('IDs with no face in the table: %s' % sorted(missing))
    return pairs


# --------------------------------------------------------------------------
# Drawing
# --------------------------------------------------------------------------
def derive_art(drawable):
    """Glyph and corners for the cards whose art is just their own value.

    Numbers and the plain D/S/R need no per-card entry: the symbol *is* the
    value. Everything else is in FACE, because the value does not determine what
    is printed.
    """
    suffix = drawable.split('_', 2)[2] if drawable.count('_') >= 2 else ''
    if suffix.isdigit():
        return dict(glyph=suffix, corners=suffix)
    letter = {'d': 'D', 's': 'S', 'r': 'R'}.get(suffix)
    if letter:
        return dict(glyph=letter, corners=letter)
    # Wilds carry a word, and everything else is in FACE. Falling through with no
    # glyph is legitimate: the specials that are word-only have nothing in the
    # middle of the panel.
    return dict(glyph=None, corners=None)


def suit_of(card_id):
    body = card_id[3:]
    for name in SUIT:
        if body.startswith(name + '_'):
            return name
    return None


def draw_svg(drawable, spec):
    suit = suit_of(spec['id']) if 'id' in spec else None
    is_wild = drawable.startswith('card_wild')

    art = FACE.get(drawable, {})
    if 'glyph' not in art:
        art = dict(art, **derive_art(drawable))
    body = []

    if is_wild:
        # A wild has no panel. It has a dark ground with a pale *disc* in the
        # middle, which is what card_wild.png shows and what the wheel sits on.
        body.append(frame(None, DARK))
        cy = H / 2.0
        body.append('  <circle cx="%.1f" cy="%.1f" r="%.1f" fill="%s"/>\n'
                    % (W / 2.0, cy, 23, PANEL_BONE))
    else:
        body.append(frame(None, SUIT[suit]))
        body.append(panel())

    glyph = art.get('glyph')
    corners = art.get('corners', glyph)

    if is_wild:
        # Same anchor and same pivot for both, or the rotated copy lands off the
        # card: text anchored 'end' at the pivot extends leftwards before the
        # rotation and rightwards after it, which is how the bottom-right corner
        # ended up clipped past the edge in the first render.
        body.append(txt(8, 17, corners, 9, PANEL_BONE, anchor='start', weight='bold'))
        body.append(txt(W - 8, H - 9, corners, 9, PANEL_BONE, anchor='start',
                        weight='bold',
                        transform='rotate(180 %.1f %.1f)' % (W - 8, H - 9)))
        body.extend(motif_pinwheel(cy))
        # "Wild" over the wheel, one colour per letter as the original does.
        body.append(txt(W / 2.0, cy + 7, 'Wild', 15, '#FFFFFF', weight='bold',
                        extra=' stroke="#141414" stroke-width="1.1"'
                              ' paint-order="stroke" stroke-linejoin="round"'))
        return assemble(body)

    # -- motif behind the glyph
    if art.get('motif') == 'arrows':
        body.extend(motif_arrows(art.get('arrow_colour', '#C70E26'),
                                 PANEL[1] + PANEL[3] / 2.0,
                                 art.get('arrows', 4)))
    elif art.get('motif') == 'crown':
        body.append(motif_crown(GOLD, 31))
    elif art.get('motif') == 'target':
        body.append(motif_target(22, PANEL[1] + PANEL[3] / 2.0 - 4))

    # -- the words over the panel
    top, bottom = art.get('words', (None, None))
    slot = art.get('word_slot', 'split')
    fill = art.get('word_fill', '#FFFFFF')
    if top:
        y = WORD_Y['top'] if slot == 'split' else WORD_Y[slot]
        body.append(outlined(W / 2.0, y, top, 11, fill))
    if bottom:
        y = WORD_Y['bottom'] if slot == 'split' else WORD_Y[slot]
        body.append(outlined(W / 2.0, y, bottom, 11, fill))

    # -- the big glyph
    if glyph:
        gcolour = art.get('glyph_fill', SUIT[suit])
        gsize = art.get('glyph_size')
        if gsize is None:
            gsize = 24 if art.get('motif') == 'target' else (26 if top or bottom else 38)
        if art.get('motif') == 'crown':
            y = 92
        elif art.get('motif') == 'target':
            y = PANEL[1] + PANEL[3] / 2.0 + 4
        elif top or bottom:
            y = 92
        else:
            y = 92
        body.append(txt(W / 2.0, y, glyph, gsize, gcolour))

    body.extend(corner_glyphs(corners, PANEL_BONE))
    return assemble(body)


def assemble(body):
    return ('<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d"'
            ' viewBox="0 0 %d %d">\n' % (W, H, W, H)
            + ''.join(body)
            + '</svg>\n')


def draw_back():
    """The card back: dark ground, bone border, and the logo block on a diagonal."""
    body = [frame(None, DARK)]
    body.append('<rect x="3.5" y="3.5" width="%d" height="%d" rx="5" fill="none"'
                ' stroke="#D7D3CB" stroke-width="2.6"/>\n' % (W - 7, H - 7))
    cx, cy = W / 2.0, H / 2.0
    body.append('  <g transform="rotate(-45 %.1f %.1f)">\n' % (cx, cy))
    body.append('    <rect x="%.1f" y="%.1f" width="46" height="34" rx="7"'
                ' fill="#FF0000" stroke="%s" stroke-width="2.4"/>\n'
                % (cx - 23, cy - 17, GOLD))
    body.append(txt(cx, cy + 8, 'HDU', 20, GOLD, weight='bold'))
    body.append('  </g>\n')
    return assemble(body)


# --------------------------------------------------------------------------
def main():
    pairs = read_table()
    os.makedirs(OUT, exist_ok=True)

    written = []
    for card_id, drawable in pairs:
        svg = draw_svg(drawable, {'id': card_id})
        path = os.path.join(OUT, drawable + '.svg')
        open(path, 'w').write(svg)
        written.append(drawable)

    open(os.path.join(OUT, 'card_back.svg'), 'w').write(draw_back())
    written.append('card_back')

    # The badge is not a card face but it is drawn at the card's aspect ratio,
    # so it comes along.
    print('wrote %d files to %s' % (len(written), OUT))
    return written


if __name__ == '__main__':
    main()