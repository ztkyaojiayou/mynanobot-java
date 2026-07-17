Nanobot CLI - AI Agent Programming Assistant
============================================

REQUIREMENTS
  - JDK 17+

SETUP
  1. Set your API key in config.yaml
  2. Add this directory to PATH:
       Windows: set PATH=%PATH%;C:\tools\nanobot
       Linux:   export PATH=/opt/nanobot:$PATH

USAGE
  cd /your-project
  nanobot              # start CLI in current directory
  nanobot -w /path     # specify workspace
  /init                # analyze project, generate NANOBOT.md
  /help                # list all commands
  /exit                # quit

COMMANDS
  /exit /q /quit       Exit
  /clear               Clear context
  /mode plan|default   Switch permission mode
  /init                Generate NANOBOT.md
  /help                Show help
