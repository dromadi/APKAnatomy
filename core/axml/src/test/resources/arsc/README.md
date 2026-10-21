# Resource table fixtures

Built with aapt2 from `source-res/` and `AndroidManifest.xml` (build-tools 36.0.0, android-37.0):

```
aapt2 compile -o c.zip --dir source-res
aapt2 link --manifest AndroidManifest.xml -I android.jar -o dense.apk c.zip
sed 's/minSdkVersion="26"/minSdkVersion="35"/' AndroidManifest.xml > m35.xml
aapt2 link --manifest m35.xml -I android.jar -o compact.apk --enable-compact-entries --min-sdk-version 35 c.zip
python3 make_sparse.py dense.apk sparse.apk
```

- `dense.arsc`: 32-bit entry offsets.
- `compact.arsc`: FLAG_OFFSET16 tables with compact entries.
- `sparse.arsc`: aapt2 36 would not emit sparse tables for this input, so `make_sparse.py`
  rewrites the sparsely populated chunks (`in`, `in-v28`) to FLAG_SPARSE.
  `aapt2 dump resources` prints the same output for dense.apk and sparse.apk.
- `network_security_config.axml`: compiled `source-res/xml/network_security_config.xml`.
