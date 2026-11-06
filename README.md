# Imob-Pro Kotlin API

Backend do Imob-Pro em Kotlin + Spring Boot, com arquitetura multiempresa e isolamento por organização.

> **Estado:** fundação, autenticação, gestão básica de membros e inventário de imóveis em implementação. Não declarar produção pronta até concluir os módulos, revisar segurança e executar os testes de integração.

## Stack
- Kotlin 2.2.21 / Java 21 / Spring Boot 3.5.7
- Spring Web, Validation, Security, Data JPA e Actuator
- PostgreSQL 17 + Flyway
- springdoc OpenAPI
- JUnit 5, MockK e Testcontainers
- Gradle Kotlin DSL, ktlint, Detekt e GitHub Actions

## Executar localmente
1. Instale JDK 21, Gradle 8.14+ e Docker Desktop.
2. Inicie o banco: `docker compose up -d postgres`.
3. Execute: `gradle bootRun`.
4. Verifique `http://localhost:8080/api/v1/health`, `/actuator/health` e `/swagger-ui.html`.

As credenciais do Compose são exclusivamente para desenvolvimento. Não as reutilize em produção.

## Funcionalidades implementadas
### Autenticação
- Registro inicial de organização e OWNER.
- Login, logout e renovação de tokens.
- Senhas BCrypt; tokens aleatórios opacos com hashes persistidos.
- Principal de segurança inclui usuário, organização e papel.

### Organizações e membros
- Consultar organização atual e listar membros.
- Atualizar papéis e remover membros logicamente.
- Proteção de operações administrativas por papel.

### Imóveis
- Criar, consultar, listar com paginação/filtros, atualizar e arquivar imóveis.
- Referência única por organização.
- Todas as queries são filtradas pelo tenant autenticado.

## Endpoints principais
| Método | Caminho | Descrição |
|---|---|---|
| POST | /api/v1/auth/register | Criar organização e usuário OWNER |
| POST | /api/v1/auth/login | Autenticar |
| POST | /api/v1/auth/refresh | Renovar tokens |
| POST | /api/v1/auth/logout | Revogar sessão |
| GET | /api/v1/auth/me | Perfil autenticado |
| GET | /api/v1/organizations/current | Organização ativa |
| GET | /api/v1/organizations/current/members | Membros da organização |
| PATCH | /api/v1/organizations/current/members/{userId}/role | Alterar papel |
| DELETE | /api/v1/organizations/current/members/{userId} | Remover membro |
| GET | /api/v1/properties | Listar imóveis |
| POST | /api/v1/properties | Criar imóvel |
| GET | /api/v1/properties/{propertyId} | Consultar imóvel |
| PUT | /api/v1/properties/{propertyId} | Atualizar imóvel |
| DELETE | /api/v1/properties/{propertyId} | Arquivar imóvel |

## Multi-tenancy e segurança
A associação usuário-empresa é explícita. O tenant ativo é resolvido a partir da sessão persistida e membership validada, nunca de um organization_id arbitrário enviado pelo cliente. Operações de domínio usam o organizationId do principal autenticado em cada consulta e alteração.

Hibernate permanece em `validate`; Flyway controla o schema. Tokens não são armazenados em texto puro. A autorização por papéis complementa, mas não substitui, a filtragem tenant-scoped.

## Testes e CI
A suíte inclui teste unitário de health e teste de integração com PostgreSQL 17 via Testcontainers cobrindo registro, perfil, logout e tentativa de leitura cross-tenant. O GitHub Actions executa build/testes e ktlint. Consulte o status da execução antes de considerar qualquer entrega validada.

## Ainda não pronto para produção
Faltam módulos do legado (imagens, anúncios, leads, CRM, visitas, tarefas, mensagens, notificações, automações e configurações), fluxo de convite/verificação/recuperação de conta, rate limiting, CORS allowlist, auditoria, gestão de arquivos, testes de carga, observabilidade e deploy. A integração com a interface original ainda precisa ser feita e validada por contrato.

## Segurança
Não publicar em produção antes de revisar configuração de segredos, CORS, rate limiting, política de criação de contas, recuperação de senha, logs sem dados sensíveis, backups, monitoramento e testes completos.
