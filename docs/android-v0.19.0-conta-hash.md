# ES Medição 0.19.0 — conexão salva e arquivos sem duplicidade

VersionCode 37. Integração com o IntegraNR, schema nr13.

## Conta lembrada

Em Sincronizar, faça login e mantenha marcada a opção **Manter conectado neste dispositivo**. Nos próximos envios aparece a conta conectada, sem pedir a senha novamente. **Sair e esquecer** remove a conexão local.

A senha não é gravada. A sessão fica em arquivo privado criptografado, protegido pelo Android Keystore e excluído do backup. O aplicativo renova a sessão quando necessário. Falha temporária de rede não apaga a conexão; sessão revogada exige novo login. Mudança de usuário ou empresa é recusada. MFA permanece fora do escopo desta versão.

## Hash e confirmação

Antes de gravar cada arquivo selecionado, o aplicativo consulta uma prévia autenticada. O servidor calcula o SHA-256 do conteúdo técnico, independentemente do horário de importação e do identificador local da revisão. O hash é devolvido ao Android e mostrado no Med.Online.

A identidade usa série/modelo do instrumento e nome do arquivo. Matrizes offline sem série mantêm sua linhagem explícita; nomes iguais não unem arquivos de instrumentos diferentes. O conteúdo técnico preserva metadados, valores, estados e origem. Os JSONs completos e evidências brutas continuam guardados.

Ao encontrar um arquivo existente, aparece **Arquivo já existe. Sobrescrever?**, com os hashes e as ações Manter existente/Sobrescrever. Mesmo conteúdo reutiliza o registro sem outra cópia. Conteúdo alterado pode substituir a versão atual após confirmação; a anterior fica no histórico. Arquivo já incorporado à inspeção não é alterado. Confirmação desatualizada é recusada.

## Validação

- 81 testes unitários Android aprovados.
- 34 verificações no emulador com armazenamento isolado e transporte simulado: criptografia, adulteração, reabertura, renovação, revogação, conta/empresa, confirmação e hashes.
- 54 verificações de regressão de sincronização e 44 de seleção visual aprovadas.
- Build Android e lint aprovados: zero erros, 112 avisos registrados.
- Web: 954 testes, TypeScript, build e lint dos arquivos alterados aprovados.
- Banco: 49 verificações de hash, idempotência, permissões, substituição e histórico passaram em transação revertida antes da aplicação.

Os testes simulados não equivalem a usar a conta real do operador. Nenhum arquivo pessoal foi enviado automaticamente pelos testes. As 57 capturas locais correspondem aos mesmos hashes do backup da versão anterior.

## Artefato

`artifacts/android-usb/ES-Medicao-v0.19.0.apk`

APK de desenvolvimento compatível com a instalação anterior do emulador, configurado para o backend de produção. Não foi publicado na Play Store. Instale como atualização; não desinstale para preservar as capturas.

Backup: `D:\@GitHubEnio\_deploy-backups\dm5ese-session-hash-20260919-163834`.

SHA-256 do APK 0.19.0: `2be4f651ba653fb9fce52de748e993ab68c42fa09f37045f5e91e56986badbcd`.
