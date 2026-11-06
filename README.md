# Imob-Pro Kotlin API

Backend multiempresa do Imob-Pro, construído em Kotlin + Spring Boot, com PostgreSQL, autenticação por tokens e isolamento de dados por organização.

> **Estado:** backend e BFF em evolução. Uma cópia versionada da interface HTML/CSS/JS foi adicionada em `web/` neste repositório, preservando o repositório legado original. O Compose agora sobe a interface, o BFF e a API Kotlin na mesma origem. A equivalência funcional dos 67 contratos e os testes ponta a ponta ainda estão pendentes; não é um deploy de produção.

## Stack
- Kotlin 2.2.21 / Java 21 / Spring Boot 3.5.7
- Spring Web, Validation, Security, Data JPA, JDBC e Actuator
- PostgreSQL 17 + Flyway
- Spring Mail + springdoc OpenAPI
- JUnit 5, MockK e Testcontainers
- Gradle Kotlin DSL, ktlint, Detekt e GitHub Actions

## Executar localmente

### Apenas PostgreSQL + API via Gradle
1. Instale JDK 21, Gradle 8.14+ e Docker Desktop.
2. Configure as variáveis de ambiente a partir de `.env.example`.
3. Inicie o banco: `docker compose up -d postgres`.
4. Execute: `gradle bootRun`.
5. Verifique `http://localhost:8080/api/v1/health`, `/actuator/health` e `/swagger-ui.html`.

### Stack completa em Docker
`docker compose up --build -d`

O Compose sobe PostgreSQL, Redis, API Kotlin, BFF e a cópia da interface web, com volume persistente para o banco e os arquivos enviados. A interface é servida pelo Nginx interno e o BFF a publica na mesma origem para manter sessão e CSRF. Credenciais padrão do Compose são apenas para desenvolvimento; altere todos os segredos antes de qualquer ambiente compartilhado.

## Funcionalidades implementadas

### Identidade e organização
- Registro inicial de organização e usuário OWNER.
- Login, logout, refresh com rotação de refresh token e seleção de organização.
- BCrypt; access/refresh tokens opacos e armazenados somente como hash.
- Recuperação de senha por e-mail com token de uso único e expiração.
- Verificação de e-mail por token.
- Convites de organização por e-mail, aceitação, listagem e revogação.
- Gestão de membros, papéis e remoção lógica.

### Imóveis e publicação
- CRUD tenant-scoped de imóveis, paginação, filtros e arquivamento.
- Upload de até 20 imagens por chamada, PNG/JPEG/WebP, limite de 10 MB por imagem, chave de storage aleatória e download autenticado.
- Armazenamento local configurável, fora da pasta pública, com volume Docker persistente.
- Anúncios por canal com fluxo de rascunho/publicação/pausa/arquivamento.
- Portal público por slug da organização, com listagem, detalhe, imagens e captura de leads.

### CRM e colaboração
- Leads, status, atribuição e vínculo tenant-safe a imóveis.
- Tarefas e visitas com responsável, prazo/agendamento, prioridade e status.
- Caixa de notificações por usuário, leitura individual e em lote.
- Conversas e mensagens limitadas aos participantes cadastrados.
- Favoritos, buscas salvas e coleções de imóveis por usuário/organização.
- Alteração de senha autenticada, recuperação e verificação de e-mail.
- Perfil, exportação de dados e solicitações de privacidade.
- Páginas e configurações básicas do site por organização.

### Automação e análise
- Cadastro, ativação e remoção de regras de automação.
- Outbox transacional e worker agendado para ações de criação de tarefa e notificações.
- Resumo do dashboard, atividade e visualizações por imóvel.
- Registro de visualizações públicas sem armazenar IP ou session key em texto puro.

## Endpoints principais

| Método | Caminho | Descrição |
|---|---|---|
| POST | /api/v1/auth/register | Criar organização e OWNER |
| POST | /api/v1/auth/login | Autenticar |
| POST | /api/v1/auth/organizations | Listar organizações do usuário após validar credenciais |
| POST | /api/v1/auth/refresh | Rotacionar tokens |
| POST | /api/v1/auth/logout | Revogar sessão |
| GET | /api/v1/auth/me | Perfil autenticado |
| POST | /api/v1/auth/password/forgot | Solicitar recuperação |
| POST | /api/v1/auth/password/reset | Redefinir senha |
| POST | /api/v1/me/password | Alterar senha autenticado |
| POST | /api/v1/auth/email-verification/confirm | Confirmar e-mail |
| POST | /api/v1/me/email-verification | Enviar verificação |
| GET/PATCH | /api/v1/organizations/current/settings | Consultar/alterar configurações |
| GET | /api/v1/organizations/current/members | Listar membros |
| PATCH/DELETE | /api/v1/organizations/current/members/{userId}... | Gerir membros |
| GET/POST | /api/v1/organizations/current/invitations | Listar/criar convites |
| DELETE | /api/v1/organizations/current/invitations/{id} | Revogar convite |
| GET/POST | /api/v1/properties | Listar/criar imóveis |
| GET/PUT/DELETE | /api/v1/properties/{propertyId} | Consultar/editar/arquivar imóvel |
| GET/POST | /api/v1/properties/{propertyId}/images | Listar/enviar imagens |
| GET/DELETE | /api/v1/properties/{propertyId}/images/{imageId}... | Conteúdo/remover imagem |
| GET/POST | /api/v1/leads | Listar/criar leads |
| PATCH | /api/v1/leads/{leadId}/status | Atualizar status |
| PATCH | /api/v1/leads/{leadId}/assignment | Atribuir responsável |
| GET/POST | /api/v1/crm/tasks | Listar/criar tarefas |
| PATCH | /api/v1/crm/tasks/{id}/status | Atualizar tarefa |
| GET/POST | /api/v1/crm/visits | Listar/agendar visitas |
| PATCH | /api/v1/crm/visits/{id}/status | Atualizar visita |
| GET/POST | /api/v1/listings | Listar/criar anúncios |
| PATCH | /api/v1/listings/{id}/status | Publicar/pausar/arquivar |
| GET | /api/v1/public/{organizationSlug}/listings | Portal público |
| GET | /api/v1/public/{organizationSlug}/listings/{slug} | Detalhe público |
| POST | /api/v1/public/{organizationSlug}/leads | Capturar lead público |
| GET/POST | /api/v1/conversations | Listar/criar conversas |
| GET/POST | /api/v1/conversations/{id}/messages | Ler/enviar mensagens |
| GET/PATCH | /api/v1/notifications | Consultar/atualizar notificações |
| GET/POST | /api/v1/automations | Gerir regras de automação |
| GET | /api/v1/automations/events | Monitorar eventos do outbox |
| POST | /api/v1/automations/events/{eventId}/retry | Reprocessar evento com falha |
| GET | /api/v1/dashboard/summary | Indicadores do dashboard |
| GET | /api/v1/analytics/properties/{propertyId} | Visualizações do imóvel |
| GET/POST | /api/v1/me/collections | Listar/criar coleções |
| POST | /api/v1/me/collections/{collectionId}/items | Adicionar imóvel à coleção |
| DELETE | /api/v1/me/collections/{collectionId}/items/{propertyId} | Remover imóvel da coleção |
| GET/PATCH | /api/v1/me/profile | Perfil do usuário |
| GET | /api/v1/me/export | Exportação de dados |
| POST | /api/v1/me/privacy-requests | Solicitar ação de privacidade |

A lista completa e os schemas também ficam disponíveis em `/swagger-ui.html`.

## Multi-tenancy e segurança
- O tenant vem do principal autenticado, nunca de um `organizationId` enviado pelo cliente.
- Consultas e alterações de domínio filtram por organização.
- FKs compostas impedem associações entre organizações diferentes.
- Papéis são verificados no servidor com method security.
- Tokens não são armazenados em texto puro.
- CORS usa allowlist explícita (`CORS_ALLOWED_ORIGINS`); vazio mantém o uso same-origin.
- Uploads ficam fora da pasta pública e validam assinatura básica do arquivo e tamanho.
- Hibernate usa `ddl-auto: validate`; Flyway é a única fonte de evolução do schema.

## Variáveis importantes
- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
- `IMOBPRO_STORAGE_ROOT`
- `APP_PUBLIC_BASE_URL`
- `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`
- `CORS_ALLOWED_ORIGINS`
- `SERVER_PORT`

## CI e testes
GitHub Actions executa build/testes e ktlint. A suíte PostgreSQL usa Testcontainers; quando Docker não está disponível, essa parte pode ser desabilitada pelo próprio teste, o que não equivale a validar o schema em PostgreSQL real. A execução de CI mais recente deve ser conferida no GitHub antes de mesclar.

## Trabalho ainda necessário antes de produção
- Equivalência funcional completa da interface HTML/JS com os 67 contratos Kotlin, incluindo todos os formatos antigos, IDs, permissões e respostas.
- Migração dos dados SQLite existentes para PostgreSQL com relatório de reconciliação, sem sobrescrever nem apagar a origem.
- Equivalência de todos os 67 endpoints legados ainda não migrados.
- Gestão de participantes de conversas, notificações assíncronas de todos os eventos e tratamento operacional de falhas do outbox.
- Integrações reais com portais imobiliários externos.
- Storage S3/compatível, antivírus, decodificação/reescrita de imagens, thumbnails e política de retenção.
- Rate limiting distribuído, proteção antiabuso no portal público e políticas de bloqueio.
- Testes de integração adicionais para convites, e-mail, upload, automações, analytics e isolamento de todos os módulos.
- Observabilidade, métricas, logs estruturados, alertas, backup/restore testado, gestão de segredos e deploy monitorado.
- Revisão de segurança independente, LGPD, retenção e políticas do cliente.

**Não considere o sistema pronto para produção** até fechar a lista acima, validar a integração ponta a ponta e fazer uma migração ensaiada com backup e plano de rollback.
