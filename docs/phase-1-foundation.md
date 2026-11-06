# Etapa 1 — Fundação técnica

## Incluído
- Projeto Kotlin/Spring Boot com Java 21 e Gradle Kotlin DSL.
- PostgreSQL via Docker Compose e Flyway.
- Estrutura inicial multiempresa: organizations, users e organization_memberships.
- OpenAPI/Swagger UI, health endpoint, teste unitário, ktlint, Detekt, CI e Dockerfile.

## Decisões
- Hibernate em validate; migrations controlam o schema.
- UUIDs gerados pelo PostgreSQL.
- Usuários são identidades globais; vínculo com empresas ocorre por membership.
- Nenhum endpoint funcional legado foi migrado nesta etapa.

## Critérios de aceite
- gradle clean test bootJar sem erros.
- PostgreSQL sobe via docker compose up -d postgres.
- Flyway aplica V1 sem intervenção manual.
- Health, actuator e Swagger respondem.
- CI executa build/testes e ktlint.

## Pendências
Build e testes ainda precisam ser executados no ambiente de desenvolvimento e no CI; validar versões e adicionar testes PostgreSQL/Testcontainers.
