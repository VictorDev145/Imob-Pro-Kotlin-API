# Compatibility BFF — operação e estado da integração

## O que já existe

O serviço `compat-bff` é um gateway same-origin separado da API Kotlin:

- Sessão server-side em PostgreSQL (`connect-pg-simple)); access e refresh tokens não chegam ao JavaScript nem são gravados em localStorage.
- Cookies de sessão HttpOnly, SameSite=Lax e Secure em produção.
- Token CSRF double-submit para POST/PATCH/DELETE; `public/app.js` busca `/api/csrf` e envia `X-CSRF-Token` em mutações.
- Rotação de refresh token no servidor após 401, logout/revogação e rate limit de login/cadastro.
- Proxy de arquivos estáticos para o Express legado, sem encaminhar rotas `/api` desconhecidas para o backend antigo.
- Conversão camelCase/snake_case e adapters de autenticação, cadastro de cliente no tenant público, propriedades públicas, leads, visitas, tarefas, CRM, mensagens, favoritos, notificações, coleções, buscas salvas, membros, automações e privacidade.
- Contatos de chat limitados a membros operacionais do tenant; conversas validam participantes ativos na organização.
- Upload multipart encaminhado à API Kotlin, que aceita os campos `files` e `images[]`.

## Inicialização local

1. Suba PostgreSQL e API: `docker compose up -d postgres api`.
2. Inicie o Express legado na porta 3000 para servir a interface estática.
3. Configure `PUBLIC_ORGANIZATION_SLUG` com o slug da organização/portal que publica os imóveis.
4. Configure um `BFF_SESSION_SECRET` aleatório com pelo menos 32 caracteres e `LEGACY_WEB_URL` apontando para o servidor estático antigo.
5. Suba o BFF: `docker compose up -d compat-bff`.
6. Acesse o BFF na porta 3100. Em produção, o proxy reverso deve publicar a interface e `/api` no mesmo host, encaminhando tudo ao BFF e deixando o BFF buscar os arquivos estáticos no Express.

Não use o segredo padrão do Compose em produção. Defina TLS no proxy reverso, `NODE_ENV=production`, `BFF_TRUST_PROXY=true` somente quando houver proxy confiável e restrinja o acesso direto à API Kotlin.

## Compatibilidade que ainda exige validação

A matriz original contém 67 declarações Express. O registro BFF tem 52 mapeamentos explícitos e handlers especializados adicionais, mas isso **não significa 67 contratos aceitos**. Rotas sem adapter respondem 501 deliberadamente; não há fallback silencioso para o Express antigo.

Bloqueios restantes:
- IDs SQLite numéricos ainda não foram migrados para UUIDs de domínio. A interface atual foi ajustada para aceitar UUIDs nos fluxos principais, mas dados históricos ainda dependem de importação/mapeamento de domínio.
- A criação/edição de imóveis precisa de ensaio real com formulários, fotos, papéis OWNER/ADMIN e payloads legados completos.
- O cadastro de proprietário/anunciante e gestão de membros precisa de fluxo de convite e validação de papel; não se deve elevar automaticamente um cliente a administrador.
- Estados do pipeline legado `visit` e `proposal` são projetados para `QUALIFIED`/workflow atual onde a API ainda não tem equivalência completa.
- Coleções e favoritos precisam de testes visuais de detalhes/imagens; conversas precisam de ensaio com dois usuários reais.
- Endpoints legados não usados pela interface principal (admin messages, analytics, privacy, automations, settings) ainda precisam de testes de contrato individuais.
- A interface só deve trocar de backend após teste de ponta a ponta com o SQLite real e com o tenant público configurado.

## Critérios para retirar o Express

- Todos os 67 endpoints têm contrato automatizado ou remoção aprovada.
- Nenhuma rota privada cai no backend antigo.
- Todas as mutações passam pelo CSRF e autorização tenant-scoped.
- Testes de browser cobrem cadastro, login, logout, busca, detalhes, favoritos, chat, leads, visitas, tarefas, imóveis e uploads.
- A exportação/importação foi ensaiada com dados reais, reconciliação sem divergências e rollback aprovado.
- O servidor Express legado pode ser desligado sem afetar assets nem APIs.
