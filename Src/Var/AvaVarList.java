import org.apache.commons.cli.*;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.core.dom.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class AvaVarList {
    public static void main(String[] args) throws IOException, ParseException {
        Options options = new Options();

        Option input = new Option("i", "input", true, "input java file path");
        input.setRequired(true);
        options.addOption(input);

        Option output = new Option("o", "output", true, "output file path");
        output.setRequired(true);
        options.addOption(output);

        Option method = new Option("m", "method", true, "method name");
        method.setRequired(true);
        options.addOption(method);

        Option srcPaths_temp = new Option("s", "srcpaths", true, "src paths file");
        srcPaths_temp.setRequired(true);
        options.addOption(srcPaths_temp);

        Option classpathEntries_temp = new Option("c", "classpathentries", true, "classpath entries file");
        classpathEntries_temp.setRequired(true);
        options.addOption(classpathEntries_temp);

        BasicParser basicParser = new BasicParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd;

        try {
            cmd = basicParser.parse(options, args);
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("utility-name", options);

            System.exit(1);
            return;
        }

        String inputFilePath = cmd.getOptionValue("input");
        String outputFilePath = cmd.getOptionValue("output");
        String methodName = cmd.getOptionValue("method");
        String srcPathsFilePath = cmd.getOptionValue("srcpaths");
        String classpathEntriesFilePath = cmd.getOptionValue("classpathentries");

        String fileContent = new String(Files.readAllBytes(Paths.get(inputFilePath)));

        ASTParser astParser = ASTParser.newParser(AST.JLS14);
        astParser.setResolveBindings(true);
        astParser.setBindingsRecovery(true);
        astParser.setKind(ASTParser.K_COMPILATION_UNIT);

        astParser.setCompilerOptions(JavaCore.getOptions());

        List<String> srcPaths = Files.readAllLines(Paths.get(srcPathsFilePath));
        List<String> classpathEntries = Files.readAllLines(Paths.get(classpathEntriesFilePath));

        String[] encodings = new String[classpathEntries.size()];
        Arrays.fill(encodings, "");
        astParser.setEnvironment(srcPaths.toArray(new String[0]), classpathEntries.toArray(new String[0]), encodings, true);

        astParser.setUnitName(inputFilePath);
        astParser.setSource(fileContent.toCharArray());

        CompilationUnit cu = (CompilationUnit) astParser.createAST(null);

        Files.write(Paths.get(outputFilePath), "".getBytes());

        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                if (node.getName().getIdentifier().equals(methodName)) {
                    appendToFile("Method: " + node.getName(),outputFilePath);

                    AbstractTypeDeclaration typeDecl = (AbstractTypeDeclaration) node.getParent();
                    ITypeBinding typeBinding = typeDecl.resolveBinding();

                    for (Object bodyDecl : typeDecl.bodyDeclarations()) {
                        if (bodyDecl instanceof FieldDeclaration) {
                            VariableDeclarationFragment vdf = (VariableDeclarationFragment) ((FieldDeclaration) bodyDecl).fragments().get(0);
                            appendToFile("Class-level variable name: " + vdf.getName(),outputFilePath);
                            appendToFile("Class-level variable type: " + vdf.resolveBinding().getType().getName(),outputFilePath);
                        }
                    }

                    while (typeBinding.getSuperclass() != null) {
                        typeBinding = typeBinding.getSuperclass();
                        for (IVariableBinding var : typeBinding.getDeclaredFields()) {
                            appendToFile("Inherited variable name: " + var.getName(), outputFilePath);
                            appendToFile("Inherited variable type: " + var.getType().getName(),outputFilePath);
                        }
                    }

                    List parameters = node.parameters();
                    for (Object parameter : parameters) {
                        if (parameter instanceof SingleVariableDeclaration) {
                            SingleVariableDeclaration singleVariableDeclaration = (SingleVariableDeclaration) parameter;
                            appendToFile("Parameter name: " + singleVariableDeclaration.getName(),outputFilePath);
                            appendToFile("Parameter type: " + singleVariableDeclaration.getType(),outputFilePath);
                        }
                    }

                    node.accept(new ASTVisitor() {
                        @Override
                        public boolean visit(VariableDeclarationFragment node) {
                            SimpleName name = node.getName();
                            appendToFile("Method-level variable name: " + name,outputFilePath);
                            appendToFile("Method-level variable type: " + node.resolveBinding().getType().getName(),outputFilePath);
                            return false;
                        }
                    });
                }
                return super.visit(node);
            }
        });
    }

    private static void appendToFile(String content, String outputFilePath) {
        try {
            Files.write(Paths.get(outputFilePath), (content + "\n").getBytes(), StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
