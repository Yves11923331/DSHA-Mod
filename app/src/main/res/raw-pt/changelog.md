# DSHA dsha.v2 / build311

- Configurações · Autorizações do dispositivo ganha « Arquivos entre apps »: autorize pasta por pasta pelo seletor de documentos do sistema, ative ou desative a escrita do assistente por pasta e revogue quando quiser.
- Novos endpoints `/app/saf/*`: listar, ler (texto, base64, por faixas), gravar texto pequeno, trazer um arquivo para o contêiner, enviar o arquivo de volta, criar pasta e excluir com confirmação. Apenas ids de pastas autorizadas são aceitos.
- Base atualizada para o build310: identidade de publicação alternável, correções de notificações da ilha, correções de fronteira em backup e origem de instalação pelo bridge; o Dock em paisagem, a tela sempre ligada e os ícones de inverno são mantidos.
- Escopo de verificação: compilação offline e checagens de código locais aprovadas; a validação no aparelho (autorização, leitura/escrita e revogação) vem após a compilação na nuvem. versionCode 311 (base build310), versionName dsha.v2, pacote dsga.mod.
