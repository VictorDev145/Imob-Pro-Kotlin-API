# Phase 6 — portal público, conta e operação

## Entregas desta fase
- Upload de imagens por imóvel, com limite de 20 por chamada e 10 MB por arquivo.
- Detecção por assinatura de PNG, JPEG e WebP; nomes originais tratados como metadados, chaves aleatórias segregadas por organização.
- Download autenticado de mídia; imagens de anúncios publicados são servidas por rota pública vinculada ao anúncio.
- Portal público por slug da organização: listagem, detalhe, imagens e captura de lead.
- Publicação de anúncio ativa o imóvel, para que o portal exiba somente inventário ativo e publicado.
- Contagem de visualizações com hash opcional de sessão, sem persistir IP nem session key em texto puro.
- Recuperação, alteração e verificação de e-mail; convites de organização com expiração, aceitação e revogação.
- Configurações e páginas públicas por organização.
- Solicitações de privacidade, perfil, exportação de dados e coleções de imóveis.
- Dashboard e analytics de visualização por imóvel.
- Regras de automação com outbox e worker agendado para criar tarefas e emitir notificações.

## Validação
- A suíte de integração com PostgreSQL aplica as migrations V1–V10 e cobre autenticação, isolamento cross-tenant, imóvel, lead, publicação pública e coleções.
- CI também executa ktlint.
- As execuções referentes ao commit mais recente devem ser consultadas no GitHub antes de mesclar.

## Limitações ainda abertas
- O storage local funciona com volume persistente, mas não substitui S3/compatível, antivírus, decodificação/recompressão, thumbnails ou CDN.
- A verificação de e-mail está disponível como fluxo, mas a política de bloqueio de recursos até confirmar o endereço precisa ser definida.
- Falhas de automação são marcadas no outbox; falta painel operacional de reprocessamento e métricas.
- As regras não executam código arbitrário e só aceitam ações explícitas.
- O portal público é multiempresa por slug, mas o frontend antigo ainda não resolve esse slug nem consome os contratos versionados.
- A integração dos 67 endpoints Express, autenticação browser-safe e migração dos dados SQLite continuam pendentes.
