# DSHA dsha.v3 / build312

- Novo plugin de orientação `dsh-saf-guide`: o acesso a arquivos entre apps entra no prompt do sistema (a porta é lida só de `/root/.dsh/.bridge_port`, nunca presumida como 3090), então o assistente acha esse canal sem instalar skill; a seção desaparece quando o recurso não está disponível.
- O acesso entre apps chega à versão coexistente: nova linha nas permissões, página de gestão (escolher pasta / permitir escrita / remover), navegador somente leitura e nove endpoints `/app/saf/*`.
- A base continua build310 (`f0f11905`); o Dock em paisagem, a tela sempre ligada e os ícones de inverno são mantidos.
- Escopo de verificação: compilação offline e as sete portas `preBuild` aprovadas; a autorização, leitura/escrita e revogação no aparelho serão verificadas após a instalação. versionCode 312, versionName dsha.v3, pacote dsga.mod.
