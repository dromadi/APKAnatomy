import struct, sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
z = zipfile.ZipFile(src); arsc = z.read('resources.arsc')
def chunks(b, start, end):
    p = start
    while p < end:
        t, h, s = struct.unpack_from('<HHI', b, p); yield p, t, h, s; p += s
th = struct.unpack_from('<H', arsc, 2)[0]
out_table = bytearray(arsc[:th]); converted = 0
for p, t, h, s in chunks(arsc, th, len(arsc)):
    if t != 0x200: out_table += arsc[p:p+s]; continue
    pkg = bytearray(arsc[p:p+h])
    for q, t2, h2, s2 in chunks(arsc, p+h, p+s):
        c = arsc[q:q+s2]
        flags = c[9]; count = struct.unpack_from('<I', c, 12)[0]; es = struct.unpack_from('<I', c, 16)[0]
        offs = [struct.unpack_from('<I', c, h2+4*i)[0] for i in range(count)] if t2 == 0x201 and flags == 0 else None
        present = [(i, o) for i, o in enumerate(offs or []) if o != 0xffffffff]
        if offs is not None and len(present) * 100 < count * 60:
            header = bytearray(c[:h2]); header[9] = flags | 1
            struct.pack_into('<I', header, 12, len(present))
            index = b''.join(struct.pack('<HH', i, o // 4) for i, o in present)
            entries = c[es:]
            struct.pack_into('<I', header, 16, h2 + len(index))
            new = header + index + entries
            struct.pack_into('<I', new, 4, len(new)); pkg += new; converted += 1
        else:
            pkg += c
    struct.pack_into('<I', pkg, 4, len(pkg)); out_table += pkg
struct.pack_into('<I', out_table, 4, len(out_table))
with zipfile.ZipFile(dst, 'w') as w:
    for info in z.infolist():
        data = bytes(out_table) if info.filename == 'resources.arsc' else z.read(info.filename)
        w.writestr(info, data)
print('converted', converted, 'type chunks')
