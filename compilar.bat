@echo off
if not exist out mkdir out
javac -encoding UTF-8 -d out src\Main.java src\TesteTP1.java src\model\Carro.java src\dao\ArquivoSequencial.java src\service\Importador.java src\service\OrdenacaoExterna.java src\util\CsvUtil.java
if %errorlevel% neq 0 exit /b %errorlevel%
echo Compilacao concluida.
