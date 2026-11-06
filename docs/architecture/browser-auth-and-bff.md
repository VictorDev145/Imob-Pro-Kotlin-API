# Autenticação do navegador e camada de compatibilidade

## Arquitetura recomendada

A interface antiga usa uma função central `api(url, opt)` e espera sessão por cookie. Não entregar o refresh token ao JavaScript e não armazená-lo em `localStorage` ou `sessionStorage`.

Para preservar o frontend sem mudar os 67 contratos de uma vez, a arquitetura recomendada é:

1. O navegador fala somente com o mesmo domínio que entrega a interface.
2. Um BFF (pode ser o Express legado durante a transição) mantém access/refresh tokens Kotlin no armazenamento de sessão do servidor.
3. O navegador recebe apenas cookie de sessão `HttpOnly; Secure; SameSite=Lax` (ou política mais restrita compatível com o domínio).
4. O BFF renova tokens no servidor e encaminha as chamadas para `/api/v1`.
5. O BFF converte nomes de campos e status HTTP entre o contrato antigo e o contrato Kotlin.
6. A sessão do BFF deve migrar para armazenamento suportado em produção (PostgreSQL ou Redis); não manter o SQLite legado como dependência indefinida.
7. Para qualquer rota que autentique por cookie, aplicar proteção CSRF (token sincronizador ou double-submit), validar Origin/Referer e limitar CORS a origens explicitamente permitidas.

## Por que não usar bearer token em armazenamento do navegador

Um refresh token em armazenamento acessível ao JavaScript pode ser extraído por XSS. O access token pode ficar somente em memória em uma integração SPA direta, mas a compatibilidade com o modelo de sessão atual é melhor atendida pelo BFF, que mantém ambos os tokens fora do navegador.

## Contrato do adaptador

O adaptador deve preservar:
- os paths e métodos da matriz de 67 endpoints;
- nomes de campos legados como `property_id`, `assigned_to`, `visit_date` e `whatsapp`;
- respostas legadas como `{ok:true}`, `{error:string}`, usuário ou `null` em `/api/auth/me`;
- IDs e status legados somente na borda, com mapeamento explícito para UUIDs e enums Kotlin;
- as permissões por organização e ownership verificadas novamente no domínio Kotlin.

O adaptador nunca deve confiar em `organization_id`, `owner_id`, `assigned_to` ou `role` fornecidos pelo browser. A organização ativa vem da sessão autenticada e do membership.

## Critérios de aceite

- Teste CSRF negativo para cada mutação autenticada por cookie.
- Teste de expiração e rotação de refresh concorrente.
- Testes de contrato para os 67 paths.
- Teste de autorização cross-tenant em cada família de recursos.
- Nenhum token em resposta de perfil, logs, HTML ou armazenamento do browser.
- O Express pode ser desligado sem quebrar a interface após a conclusão do BFF e dos testes de contrato.
