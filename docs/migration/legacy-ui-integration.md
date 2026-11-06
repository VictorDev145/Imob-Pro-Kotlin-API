# Integração da interface legada — plano e bloqueios

## Regra de preservação
A interface original continua no repositório `VictorDev145/Imob-Pro`. A API Kotlin usa rotas versionadas em `/api/v1`. Não substituir `public/app.js` nem apagar o servidor Express antes de existir uma camada de compatibilidade validada.

A matriz de 67 rotas foi levantada na Etapa 0 em `Imob-Pro/docs/api/compatibility-matrix.md`, na branch `docs/phase-0-kotlin-api-contracts`.

## O que já possui implementação de domínio na API Kotlin
- Identidade: registro, login, logout, refresh, recuperação, alteração de senha e verificação de e-mail.
- Organizações: memberships, papéis, convites e seleção da organização ativa.
- Portal: anúncios públicos por `organizationSlug`, detalhe, imagens públicas, captura de lead e contagem de visualização.
- Operação: imóveis, imagens, leads, tarefas, visitas, notificações e atividade.
- Cliente: favoritos, buscas salvas, coleções, perfil, exportação e solicitações de privacidade.
- Colaboração: conversas e mensagens com membership explícito.
- Administração: configurações do site, páginas, regras de automação, dashboard e analytics.

## Por que a interface ainda não está conectada
O frontend atual chama caminhos legados como `/api/auth/login`, `/api/properties`, `/api/admin/leads`, `/api/owner/properties` e `/api/messages`. A API Kotlin oferece contratos versionados diferentes, com UUIDs, enums em maiúsculas, paginação e modelos multiempresa. Não existe ainda um controlador de compatibilidade que traduza todos os caminhos, payloads, respostas e códigos HTTP.

A autenticação também mudou de sessão Express para access/refresh tokens. Não se deve guardar refresh token em localStorage/sessionStorage. A integração precisa de um BFF/cookie HttpOnly com proteção CSRF ou de uma estratégia equivalente revisada antes de colocar a interface no mesmo fluxo.

## Etapas obrigatórias para fechar a integração
1. Definir organização pública padrão por host/domínio e como a interface resolve o slug da organização.
2. Criar uma camada compatível com os caminhos legados, preservando snake_case e os formatos esperados pelo `app.js`.
3. Implementar transporte de autenticação browser-safe: cookie HttpOnly/Secure/SameSite, rotação de refresh e proteção CSRF; não expor refresh token ao JavaScript.
4. Traduzir os IDs e enums sem alterar o banco: os IDs legados numéricos não podem ser tratados como UUIDs.
5. Completar coleções, notificações, conversas, área de anunciante, dashboards e permissões com contratos equivalentes.
6. Migrar SQLite para PostgreSQL com script idempotente, mapeamento de usuários/organizações, imagens e relatório de reconciliação. Preservar o banco SQLite como fonte de rollback.
7. Rodar testes de contrato automatizados entre cada fluxo do `public/app.js` e o adapter Kotlin.
8. Fazer teste manual ponta a ponta: cadastro/login, busca, imóvel, favoritos, lead, anúncio, upload, visita, CRM, mensagens, privacidade e reinício do servidor.

## Critério de conclusão
A integração só pode ser marcada como concluída quando todos os 67 contratos estiverem classificados como compatíveis, substituídos com alteração correspondente e testada no frontend, ou formalmente removidos da interface. Até lá, o Express e o banco SQLite originais devem permanecer preservados e a PR Kotlin deve continuar sem merge de produção.
