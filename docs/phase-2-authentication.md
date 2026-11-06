# Fase 2 — Autenticação e gestão multiempresa

## Entregue nesta branch
- Registro transacional cria organização, usuário e membership OWNER.
- Login valida credenciais e membership ativa; organizationId deve ser informado quando a identidade possuir mais de uma empresa ativa.
- Access tokens opacos com validade de 15 minutos; refresh tokens com validade de 30 dias e rotação.
- Apenas hashes SHA-256 dos tokens são persistidos; senhas usam BCrypt com custo 12.
- Filtro carrega userId, organizationId e role da sessão e membership persistidas.
- Rotas privadas exigem autenticação; autorização por função habilitada nos métodos protegidos.
- Consulta da empresa atual e listagem de membros com escopo obrigatório pela empresa do principal.
- Atualização de papéis e remoção lógica de membros; sessões da empresa são revogadas ao remover membro.

## Endpoints
- POST /api/v1/auth/register
- POST /api/v1/auth/login
- POST /api/v1/auth/refresh
- POST /api/v1/auth/logout
- GET /api/v1/auth/me
- GET /api/v1/organizations/current
- GET /api/v1/organizations/current/members
- PATCH /api/v1/organizations/current/members/{userId}/role
- DELETE /api/v1/organizations/current/members/{userId}

## Regras de autorização
- OWNER e ADMIN podem gerenciar membros; MANAGER pode consultar a lista.
- ADMIN não pode atribuir o papel ADMIN.
- Nenhum administrador pode alterar/remover o OWNER ou transferir a propriedade por este endpoint.
- Usuário não pode alterar o próprio papel nem remover a própria membership.
- Queries de membros são sempre filtradas pelo organizationId presente no principal autenticado.

## Testes
AuthFlowIntegrationTest usa PostgreSQL 17 via Testcontainers para verificar registro, consulta do perfil autenticado e invalidação do access token após logout. A CI precisa executar este teste em runner com Docker.

## Limitações antes de produção
Ainda faltam rate limiting, verificação de e-mail, recuperação de senha, fluxo formal de convite e transferência de propriedade, gestão de sessões/dispositivos, CORS allowlist, auditoria e testes abrangentes cross-tenant. Build e migrations precisam ser verificados pela CI; esta fase não torna o sistema pronto para produção.
