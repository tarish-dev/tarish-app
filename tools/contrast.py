#!/usr/bin/env python3
"""Measure every colour pair the app actually puts on screen, in both themes.

Run it after touching res/values*/colors.xml:

    tools/contrast.py            # table, exits 1 if a pair regresses
    tools/contrast.py --all      # every pair, including the ones that pass

WHY THIS EXISTS. The brand kit gives ramps; res/values*/colors.xml decides which rung of
which ramp plays which role, per theme. That mapping is where contrast is won or lost, and
the failure is silent -- a token that reads fine on a near-white ground can be illegible on
a near-black one, and the only symptom is that someone cannot read a file size. Two real
defects were found this way: the dark theme reused the light theme's faint rung
(basalt_500, a MID grey, low-contrast against BOTH ends of the ramp), and the accent needed
a different rung per theme.

WHAT THE THRESHOLDS MEAN. 4.5:1 is WCAG 1.4.3 for body text. 3.0:1 is 1.4.11 for large
text and for graphics that identify a control or its state. Decoration is exempt, which is
why the hairlines are not in this table -- see the note on t_rule in res/values/colors.xml
before deciding they are a bug.
"""
import os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# (foreground token, background token, what it is on screen, minimum ratio)
PAIRS = [
    ('t_text',       't_bg',            'body text on the ground',            4.5),
    ('t_text',       't_surface',       'body text on a card',                4.5),
    ('t_text',       't_surface_sunk',  'body text in a sunk field',          4.5),
    ('t_text_muted', 't_bg',            'secondary text',                     4.5),
    ('t_text_muted', 't_surface_sunk',  'secondary text on a chip',           4.5),
    ('t_text_faint', 't_bg',            'faint text: sizes, timestamps',      4.5),
    ('t_text_faint', 't_surface',       'faint text on a card',               4.5),
    ('t_accent',     't_bg',            'accent text and icons',              4.5),
    ('t_accent',     't_surface',       'accent on a card',                   4.5),
    ('t_accent',     't_surface_sunk',  'the 76dp device glyph in its circle', 3.0),
    ('t_on_accent',  't_accent_fill',   'glyph on the active nav pill',       4.5),
    ('t_live',       't_bg',            'received / live',                    4.5),
    ('t_live',       't_surface_sunk',  'the live dot in a protocol chip',    3.0),
    ('t_error',      't_bg',            'failures',                           4.5),
    ('t_error',      't_surface',       'failures on a card',                 4.5),
    ('t_info',       't_bg',            'informational',                      4.5),
]

THEMES = {'light': 'res/values/colors.xml', 'dark': 'res/values-night/colors.xml'}


def read(path):
    return open(os.path.join(ROOT, path), encoding='utf-8').read()


def ramps():
    return dict(re.findall(r'<color name="([^"]+)">(#[0-9A-Fa-f]{6})</color>',
                           read('res/values/brand_ramps.xml')))


def semantic(path, rungs):
    """Semantic token -> hex, resolving one level of @color/ indirection."""
    out = {}
    for name, target in re.findall(
            r'<color name="(t_[^"]+)">@color/([A-Za-z_0-9]+)</color>', read(path)):
        if target not in rungs:
            sys.exit(f'{path}: {name} points at @color/{target}, which is not a brand ramp '
                     f'rung. Every colour has to come from brand_ramps.xml.')
        out[name] = rungs[target]
    return out


def luminance(hexcolor):
    channels = [int(hexcolor[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    linear = [c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4 for c in channels]
    return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]


def ratio(a, b):
    la, lb = luminance(a), luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)


def main():
    show_all = '--all' in sys.argv
    rungs = ramps()
    themes = {name: semantic(path, rungs) for name, path in THEMES.items()}

    missing = {t: [tok for pair in PAIRS for tok in pair[:2] if tok not in themes[t]]
               for t in themes}
    for theme, absent in missing.items():
        if absent:
            sys.exit(f'{THEMES[theme]} defines no {", ".join(sorted(set(absent)))}. '
                     'Both themes must answer every token or the app falls back to the '
                     'other theme\'s value for it.')

    failures = []
    print(f'{"":<40}{"light":>15}{"dark":>15}   min')
    for fg, bg, role, floor in PAIRS:
        cells = []
        row_fails = False
        for theme in ('light', 'dark'):
            r = ratio(themes[theme][fg], themes[theme][bg])
            ok = r >= floor
            row_fails |= not ok
            if not ok:
                failures.append((theme, fg, bg, role, r, floor))
            cells.append(f'{r:6.2f}:1 {"ok" if ok else "FAIL"}')
        if show_all or row_fails:
            print(f'{role:<40}{cells[0]:>15}{cells[1]:>15}   {floor}')

    print()
    if not failures:
        print(f'{len(PAIRS)} pairs x 2 themes: all pass')
        return 0
    for theme, fg, bg, role, r, floor in failures:
        print(f'FAIL  {theme:<5} {fg} on {bg}: {r:.2f}:1, needs {floor} — {role}')
    print()
    print('A mid rung is low-contrast against BOTH ends of the ramp. If a token fails in '
          'one theme only,\nthe fix is almost always to pick a different rung for that '
          'theme, not to change the ramp.')
    return 1


if __name__ == '__main__':
    sys.exit(main())
