"""把 chat-demo 的 icons.tsx / FileIcon.tsx 内联 SVG 转成 Android VectorDrawable。

用法：python android/scripts/svg2vd.py <仓库根目录> <输出目录>

输出全部图标；只把 Compose 中实际引用的 ic_*.xml 复制进 app/src/main/res/drawable/，
避免 lint 报未使用资源。来源与对照表见 docs/android/icons.md。
"""
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
out = Path(sys.argv[2])
out.mkdir(parents=True, exist_ok=True)

ICONS = root / 'src/components/base/icons.tsx'
FILE_ICON = root / 'src/components/files/FileIcon.tsx'

ATTR = re.compile(r'([a-zA-Z-]+)=(?:"([^"]*)"|\{([^}]*)\})')
TAG = re.compile(r'<(/?)(svg|path|g|rect|defs|clipPath)\b([^>]*?)(/?)>', re.S)


def attrs(s):
    d = {}
    for m in ATTR.finditer(s):
        d[m.group(1)] = m.group(2) if m.group(2) is not None else m.group(3)
    return d


def snake(name):
    s = re.sub(r'^Icon', '', name)
    s = re.sub(r'Icon$', '', s)
    s = re.sub(r'(?<=[a-z0-9])(?=[A-Z])', '_', s)
    s = re.sub(r'(?<=[a-zA-Z])(?=[0-9])', '_', s)
    return 'ic_' + s.lower()


def color(v, default='#FF000000'):
    if v is None or v == 'currentColor':
        return default
    if v == 'white':
        return '#FFFFFFFF'
    if v.startswith('#') and len(v) == 7:
        return '#FF' + v[1:].upper()
    return v


def n(v):
    return f'{v:.4f}'.rstrip('0').rstrip('.')


def rect_path(a):
    x = float(a.get('x', 0)); y = float(a.get('y', 0))
    w = float(a['width']); h = float(a['height']); r = float(a.get('rx', 0))
    if r == 0:
        return f'M{n(x)},{n(y)}h{n(w)}v{n(h)}h{n(-w)}z'
    a_ = f'a{n(r)},{n(r)} 0 0 1'
    return (f'M{n(x + r)},{n(y)}h{n(w - 2 * r)}{a_} {n(r)},{n(r)}v{n(h - 2 * r)}{a_} {n(-r)},{n(r)}'
            f'h{n(-(w - 2 * r))}{a_} {n(-r)},{n(-r)}v{n(-(h - 2 * r))}{a_} {n(r)},{n(-r)}z')


def convert(svg_text, source):
    """svg_text 为完整 <svg>…</svg> 片段（JSX 表达式已替换为字面量）。"""
    out_lines = []
    stack = [{'fill': None}]
    vb = None
    depth_skip = 0
    indent = 1
    for m in TAG.finditer(svg_text):
        closing, tag, raw, selfclose = m.group(1), m.group(2), m.group(3), m.group(4)
        a = attrs(raw)
        if tag in ('defs', 'clipPath'):
            # 仅包含覆盖整个 viewBox 的裁剪矩形，等价于不裁剪
            if not closing and not selfclose:
                depth_skip += 1
            elif closing:
                depth_skip -= 1
            continue
        if depth_skip:
            continue
        if tag == 'svg':
            if closing:
                continue
            vb = [float(x) for x in a['viewBox'].split()]
            continue
        if tag == 'g':
            if closing:
                stack.pop()
                indent -= 1
                out_lines.append('    ' * indent + '</group>')
                continue
            fill = a.get('fill', stack[-1]['fill'])
            tr = a.get('transform', '')
            tx = ty = 0.0
            mm = re.match(r'translate\(([-\d.]+)(?:[ ,]+([-\d.]+))?\)', tr)
            if mm:
                tx = float(mm.group(1)); ty = float(mm.group(2) or 0)
            out_lines.append('    ' * indent + f'<group android:translateX="{tx:g}" android:translateY="{ty:g}">')
            indent += 1
            stack.append({'fill': fill})
            continue
        if closing:
            continue
        if tag == 'rect':
            d = rect_path(a)
        else:
            d = a['d']
        fill = a.get('fill', stack[-1]['fill'])
        attrs_out = [f'android:pathData="{d}"']
        if fill and fill != 'none':
            attrs_out.append(f'android:fillColor="{color(fill)}"')
            if 'fillOpacity' in a:
                attrs_out.append(f'android:fillAlpha="{float(a["fillOpacity"]):g}"')
            if a.get('fillRule') == 'evenodd':
                attrs_out.append('android:fillType="evenOdd"')
        if 'stroke' in a:
            attrs_out.append(f'android:strokeColor="{color(a["stroke"])}"')
            attrs_out.append(f'android:strokeWidth="{float(a.get("strokeWidth", 1)):g}"')
        pad = '    ' * indent
        tr = a.get('transform')
        if tr:
            mm = re.match(r'translate\(([-\d.]+)(?:[ ,]+([-\d.]+))?\)', tr)
            out_lines.append(pad + f'<group android:translateX="{float(mm.group(1)):g}" android:translateY="{float(mm.group(2) or 0):g}">')
            out_lines.append(pad + '    <path\n' + '\n'.join(pad + '        ' + x for x in attrs_out) + ' />')
            out_lines.append(pad + '</group>')
        else:
            out_lines.append(pad + '<path\n' + '\n'.join(pad + '    ' + x for x in attrs_out) + ' />')
    minx, miny, w, h = vb
    body = '\n'.join(out_lines)
    if minx or miny:
        body = (f'    <group android:translateX="{-minx:g}" android:translateY="{-miny:g}">\n'
                + '\n'.join('    ' + l for l in body.split(chr(10))) +'\n    </group>')
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f'<!-- 来源：DeepSeek chat-demo（MIT），{source}；由内联 SVG 逐元素转换，图形未改动 -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{w:g}dp"\n    android:height="{h:g}dp"\n'
        f'    android:viewportWidth="{w:g}"\n    android:viewportHeight="{h:g}">\n'
        f'{body}\n'
        '</vector>\n'
    )


written = []

# icons.tsx：逐个 export
text = ICONS.read_text(encoding='utf-8')
for m in re.finditer(r'export const (\w+) = \(\) =>', text):
    name = m.group(1)
    start = text.index('<svg', m.end())
    end = text.index('</svg>', start) + len('</svg>')
    svg = text[start:end]
    xml = convert(svg, f'src/components/base/icons.tsx {name}')
    fn = snake(name) + '.xml'
    (out / fn).write_text(xml, encoding='utf-8', newline='\n')
    written.append((fn, name))

# FileIcon.tsx：外框 + 各类型内容，外框颜色按类型代入
ftext = FILE_ICON.read_text(encoding='utf-8')
frame_start = ftext.index('<svg', ftext.index('function FileIconFrame'))
frame_end = ftext.index('{children}', frame_start)
frame = ftext[frame_start:frame_end]
for func, fname in [('OtherFileIcon', 'ic_file_other'), ('PdfIcon', 'ic_file_pdf'), ('TableFileIcon', 'ic_file_table'),
                    ('ImageIcon', 'ic_file_image'), ('PptFileIcon', 'ic_file_ppt')]:
    s = ftext.index(f'function {func}')
    fs = ftext.index('<FileIconFrame', s)
    col = re.search(r'color="([^"]+)"', ftext[fs:fs + 80]).group(1)
    inner_start = ftext.index('>', fs) + 1
    inner_end = ftext.index('</FileIconFrame>', inner_start)
    svg = frame.replace('{color}', f'"{col}"') + ftext[inner_start:inner_end] + '</svg>'
    xml = convert(svg, f'src/components/files/FileIcon.tsx {func}')
    (out / f'{fname}.xml').write_text(xml, encoding='utf-8', newline='\n')
    written.append((f'{fname}.xml', func))

for fn, name in written:
    print(fn, name)
