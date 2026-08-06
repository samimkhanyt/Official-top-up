import os

KEY = b"EspTopUp_Security_Vault_2026_Secure_Key_#998123"

files_map = {
    "/app/src/main/assets/index.html": "/app/src/main/assets/sec_index.dat",
    "/app/src/main/assets/admin.html": "/app/src/main/assets/sec_admin.dat",
    "/app/src/main/assets/admin_notification.html": "/app/src/main/assets/sec_admin_notif.dat"
}

for src, dst in files_map.items():
    if os.path.exists(src):
        with open(src, "rb") as f:
            data = f.read()
        
        encrypted = bytearray(len(data))
        key_len = len(KEY)
        for i in range(len(data)):
            encrypted[i] = data[i] ^ KEY[i % key_len]
            
        with open(dst, "wb") as f:
            f.write(encrypted)
        print(f"Encrypted {src} ({len(data)} bytes) -> {dst} ({len(encrypted)} bytes)")
