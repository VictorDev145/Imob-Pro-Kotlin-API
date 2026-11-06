# Fase 2 — Autenticação e contexto multiempresa (implementação inicial)

## Incluído nesta branch
- Registro cria empresa, usuário e membership OWNER em transação.
- Login valida credenciais e membership ativa; organizationId é obrigatório quando houver mais de uma empresa ativa.
- Access tokens opacos com validade de 15 minutos; refresh tokens com validade de 30 dias e rotação.
- Somente hashes SHA-256 dos tokens são persistidos; senhas usam BCrypt (custo 12).
- Filtro carrega userId, organizationId e role da sessão e membership persistidas.
- Rotas privadas exigem autenticação.

## Endpoints
- POST /api/v1/auth/register
- POST /api/v1/auth/login
- POST /api/v1/auth/refresh
- POST /api/v1/auth/logout (Bearer)
- GET /api/v1/auth/me (Bearer)

## Limitações e próximos controles
Ainda faltam rate limiting, verificação de e-mail, recuperação de senha, gestão de sessões/dispositivos, CORS allowlist, auditoria e testes de integração cross-tenant. O registro público e as regras de convite precisam de política de negócio antes da produção. Build, migrations e testes PostgreSQL/Testcontainers ainda precisam ser executados; esta branch não está certificada para produção.
