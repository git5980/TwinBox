#!/usr/bin/env python3
"""合并 VA lib manifest 的 application 内组件到宿主 manifest 模板。
占位符：applicationId/AUTHORITY_PREFIX/PERMISSION_PREFIX/VA_VERSION
用法: python3 merge_manifest.py <host_template> <lib_manifest> <output> <pkg>
"""
import re, sys

host_tpl, lib_path, out_path, pkg = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]

lib = open(lib_path).read()
m = re.search(r'<application[^>]*>(.*)</application>', lib, re.S)
va_nodes = m.group(1)

# 相对组件名（.client.stub.X → com.lody.virtual.client.stub.X）
va_nodes = re.sub(r'(android:name=")\.([^"]*")', r'\1com.lody.virtual.\2', va_nodes)
# 相对 authorities / meta-data value 同样处理
va_nodes = re.sub(r'(android:authorities=")\.([^"]*")', r'\1com.lody.virtual.\2', va_nodes)

SUBS = {
    '${applicationId}': pkg,
    '${AUTHORITY_PREFIX}': pkg,
    '${PERMISSION_PREFIX}': pkg,
    '${VA_VERSION}': '26',
}

# 提取 lib 的 uses-permission（含自定义权限引用），合并去重
lib_perms = set(re.findall(r'<uses-permission android:name="([^"]+)"\s*/>', lib))
lib_perms = {s if not s.startswith('${') else s for s in lib_perms}

host = open(host_tpl).read()
# 移除模板里已有权限，避免重复
host_perms = set(re.findall(r'<uses-permission android:name="([^"]+)"\s*/>', host))
for p in sorted(lib_perms - host_perms):
    # 跳过带占位符的（自定义权限引用，宿主不用 SAFE_ACCESS 机制）
    if '${' in p:
        continue
    host = host.replace('<application', f'<uses-permission android:name="{p}" />\n    <application', 1)

host = host.replace('@@VA_APPLICATION@@', va_nodes)
for k, v in SUBS.items():
    host = host.replace(k, v)

# 权限声明（SAFE_ACCESS）也替换掉占位符形式
host = host.replace('${PERMISSION_PREFIX}', pkg)

open(out_path, 'w').write(host)
print(f'merged: {len(va_nodes)} chars VA nodes -> {out_path}')
# 统计
tags = re.findall(r'<(activity|service|provider|receiver) ', host)
import collections
print('final components:', dict(collections.Counter(tags)))
