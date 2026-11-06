# Imob-Pro Kotlin API

Nova API do Imob-Pro em Kotlin + Spring Boot, preparada para arquitetura multiempresa.

> Estado inicial: fundação técnica. Ainda não contém os módulos funcionais do legado e não está pronta para produção.

## Stack
- Kotlin 2.2.21 / Java 21 / Spring Boot 3.5.7
- Spring Web, Validation, Data JPA e Actuator
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

## Multi-tenancy
A associação usuário-empresa é explícita. O tenant ativo deverá ser resolvido a partir da identidade autenticada e membership validada, nunca de um organization_id arbitrário enviado pelo cliente. Hibernate permanece em validate; Flyway é a fonte de verdade do schema.

## Próximas etapas
Autenticação e autorização; tenant context e testes cross-tenant; imóveis/anúncios; CRM e demais módulos; integração gradual da interface existente; hardening e deploy.

## Segurança
Não publicar em produção antes de implementar autenticação, autorização, gestão de segredos, CORS/headers, rate limiting, observabilidade e testes de integração completos.
