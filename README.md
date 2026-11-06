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

### Imóveis e anúncios
- CRUD de imóveis com paginação, filtros e arquivamento lógico.
- Cadastro e moderação de anúncios/publicações por canal (site, portal, social e interno).
- Estrutura de metadados para imagens com chave de armazenamento por tenant; o adaptador real de storage ainda precisa ser conectado.

### CRM operacional
- Leads com status, atribuição e vínculo tenant-safe a imóveis.
- Tarefas com prioridade, prazo, responsável, lead e imóvel.
- Agendamento de visitas com duração, responsável, lead e imóvel.
- Log de atividades para eventos operacionais.
- Caixa de notificações por usuário, leitura individual e leitura em lote.
- Chaves estrangeiras compostas impedem associações entre organizações.

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
| GET | /api/v1/leads | Listar leads |
| POST | /api/v1/leads | Criar lead |
| GET | /api/v1/leads/{leadId} | Consultar lead |
| PATCH | /api/v1/leads/{leadId}/status | Atualizar status |
| PATCH | /api/v1/leads/{leadId}/assignment | Atribuir membro |
| GET/POST | /api/v1/crm/tasks | Listar/criar tarefas |
| PATCH | /api/v1/crm/tasks/{id}/status | Atualizar tarefa |
| GET/POST | /api/v1/crm/visits | Listar/agendar visitas |
| PATCH | /api/v1/crm/visits/{id}/status | Atualizar visita |
| GET/POST | /api/v1/listings | Listar/criar anúncios |
| PATCH | /api/v1/listings/{id}/status | Publicar, pausar ou arquivar anúncio |
| GET | /api/v1/notifications | Caixa de notificações |
| PATCH | /api/v1/notifications/{id}/read | Marcar notificação como lida |
| PATCH | /api/v1/notifications/read-all | Marcar todas como lidas |

## Multi-tenancy e segurança
A associação usuário-empresa é explícita. O tenant ativo é resolvido a partir da sessão persistida e membership validada, nunca de um organization_id arbitrário enviado pelo cliente. Operações de domínio usam o organizationId do principal autenticado em cada consulta e alteração.

Hibernate permanece em `validate`; Flyway controla o schema. Tokens não são armazenados em texto puro. A autorização por papéis complementa, mas não substitui, a filtragem tenant-scoped.

## Testes e CI
A suíte inclui teste unitário de health e teste de integração com PostgreSQL 17 via Testcontainers cobrindo registro, perfil, logout e tentativa de leitura cross-tenant. O GitHub Actions executa build/testes e ktlint. Consulte o status da execução antes de considerar qualquer entrega validada.

## Ainda não pronto para produção
Ainda faltam: adaptador de armazenamento e upload seguro de imagens; mensagens/conversas; automações e regras; favoritos e buscas salvas; páginas/configurações do site; convites, verificação de e-mail e recuperação de senha; rate limiting distribuído, CORS allowlist, hardening de produção, testes de carga, observabilidade, deploy e integração com a interface legada. O log de atividades e o esquema dos módulos não substituem esses fluxos completos.

## Segurança
Não publicar em produção antes de revisar configuração de segredos, CORS, rate limiting, política de criação de contas, recuperação de senha, logs sem dados sensíveis, backups, monitoramento e testes completos.
