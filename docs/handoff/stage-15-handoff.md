# Handoff: Stage 15 (Auth) 완료

## 한 줄
User + Role + Privilege RBAC. SHA-256 (학습용).

## 결정
- D-064: SHA-256 (production은 bcrypt/argon2).
- D-065: RBAC.
- D-066: In-memory only.

## 코드
- `auth.User/Role/Privilege/PasswordHasher/AuthManager`
