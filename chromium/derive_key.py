#!/usr/bin/env python3
"""A second, stable signing key for the built-in Grove extension, derived from the Grove key (so it never changes and
needs no extra secret). usage: derive_key.py GROVE_KEY.pem LABEL OUT.pem"""
import hashlib, hmac, sys
from Crypto.PublicKey import RSA

seed = hmac.new(open(sys.argv[1], "rb").read(), sys.argv[2].encode(), hashlib.sha256).digest()
state = {"n": 0}

def rand(n):
    out = b""
    while len(out) < n:
        state["n"] += 1
        out += hmac.new(seed, state["n"].to_bytes(8, "big"), hashlib.sha256).digest()
    return out[:n]

open(sys.argv[3], "wb").write(RSA.generate(2048, randfunc=rand).export_key("PEM", pkcs=8))
