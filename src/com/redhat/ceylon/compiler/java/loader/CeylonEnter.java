/*
 * Copyright Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the authors tag. All rights reserved.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU General Public License version 2.
 * 
 * This particular file is subject to the "Classpath" exception as provided in the 
 * LICENSE file that accompanied this code.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License,
 * along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */

package com.redhat.ceylon.compiler.java.loader;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.Set;

import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject.Kind;
import javax.tools.StandardLocation;

import com.redhat.ceylon.cmr.api.ArtifactContext;
import com.redhat.ceylon.cmr.api.ArtifactResult;
import com.redhat.ceylon.cmr.api.RepositoryManager;
import com.redhat.ceylon.cmr.impl.InvalidArchiveException;
import com.redhat.ceylon.compiler.java.codegen.AnnotationModelVisitor;
import com.redhat.ceylon.compiler.java.codegen.BoxingDeclarationVisitor;
import com.redhat.ceylon.compiler.java.codegen.BoxingVisitor;
import com.redhat.ceylon.compiler.java.codegen.CeylonCompilationUnit;
import com.redhat.ceylon.compiler.java.codegen.CeylonTransformer;
import com.redhat.ceylon.compiler.java.codegen.CodeGenError;
import com.redhat.ceylon.compiler.java.codegen.CompilerBoxingDeclarationVisitor;
import com.redhat.ceylon.compiler.java.codegen.CompilerBoxingVisitor;
import com.redhat.ceylon.compiler.java.codegen.DeferredVisitor;
import com.redhat.ceylon.compiler.java.codegen.DefiniteAssignmentVisitor;
import com.redhat.ceylon.compiler.java.codegen.MissingNativeVisitor;
import com.redhat.ceylon.compiler.java.codegen.UnsupportedVisitor;
import com.redhat.ceylon.compiler.java.codegen.InterfaceVisitor;
import com.redhat.ceylon.compiler.java.codegen.TypeParameterCaptureVisitor;
import com.redhat.ceylon.compiler.java.tools.CeylonLog;
import com.redhat.ceylon.compiler.java.tools.CeylonPhasedUnit;
import com.redhat.ceylon.compiler.java.tools.CeyloncFileManager;
import com.redhat.ceylon.compiler.java.tools.LanguageCompiler;
import com.redhat.ceylon.compiler.java.tools.LanguageCompiler.CompilerDelegate;
import com.redhat.ceylon.compiler.java.util.Timer;
import com.redhat.ceylon.compiler.java.util.Util;
import com.redhat.ceylon.compiler.loader.AbstractModelLoader;
import com.redhat.ceylon.compiler.loader.model.LazyModule;
import com.redhat.ceylon.compiler.typechecker.TypeChecker;
import com.redhat.ceylon.compiler.typechecker.analyzer.AnalysisError;
import com.redhat.ceylon.compiler.typechecker.analyzer.ModuleValidator;
import com.redhat.ceylon.compiler.typechecker.analyzer.UnsupportedError;
import com.redhat.ceylon.compiler.typechecker.context.PhasedUnit;
import com.redhat.ceylon.compiler.typechecker.context.PhasedUnits;
import com.redhat.ceylon.compiler.typechecker.model.Declaration;
import com.redhat.ceylon.compiler.typechecker.model.Module;
import com.redhat.ceylon.compiler.typechecker.model.ProducedType;
import com.redhat.ceylon.compiler.typechecker.model.Setter;
import com.redhat.ceylon.compiler.typechecker.model.TypedDeclaration;
import com.redhat.ceylon.compiler.typechecker.model.Unit;
import com.redhat.ceylon.compiler.typechecker.tree.Node;
import com.redhat.ceylon.compiler.typechecker.tree.Tree;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.CompilationUnit;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.CompilerAnnotation;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.Statement;
import com.redhat.ceylon.compiler.typechecker.tree.UnexpectedError;
import com.redhat.ceylon.compiler.typechecker.util.AssertionVisitor;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.PackageSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.comp.Annotate;
import com.sun.tools.javac.comp.AttrContext;
import com.sun.tools.javac.comp.Check;
import com.sun.tools.javac.comp.Enter;
import com.sun.tools.javac.comp.Env;
import com.sun.tools.javac.comp.Todo;
import com.sun.tools.javac.file.Paths;
import com.sun.tools.javac.main.OptionName;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.util.Abort;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Context.SourceLanguage.Language;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Options;

public class CeylonEnter extends Enter {

    public static CeylonEnter instance(Context context) {
        CeylonEnter instance = (CeylonEnter)context.get(enterKey);
        if (instance == null){
            instance = new CeylonEnter(context);
            context.put(enterKey, instance);
        }
        return instance;
    }

    private CeylonTransformer gen;
    private boolean hasRun = false;
    private PhasedUnits phasedUnits;
    private CompilerDelegate compilerDelegate;
    private com.redhat.ceylon.compiler.typechecker.context.Context ceylonContext;
    private Log log;
    private AbstractModelLoader modelLoader;
    private Options options;
    private Timer timer;
    private Paths paths;
    private CeyloncFileManager fileManager;
    private boolean verbose;
    private Check chk;
    private Types types;
    private Symtab symtab;
    private Todo todo;
    private boolean isBootstrap;
    private Annotate annotate;
    private Set<Module> modulesAddedToClassPath = new HashSet<Module>();
    private TaskListener taskListener;

    
    protected CeylonEnter(Context context) {
        super(context);
        // make sure it's loaded first
        CeylonClassReader.instance(context);
        try {
            gen = CeylonTransformer.getInstance(context);
        } catch (Exception e) {
            // FIXME
            e.printStackTrace();
        }
        phasedUnits = LanguageCompiler.getPhasedUnitsInstance(context);
        compilerDelegate = LanguageCompiler.getCompilerDelegate(context);
        ceylonContext = LanguageCompiler.getCeylonContextInstance(context);
        log = CeylonLog.instance(context);
        modelLoader = CeylonModelLoader.instance(context);
        options = Options.instance(context);
        timer = Timer.instance(context);
        paths = Paths.instance(context);
        fileManager = (CeyloncFileManager) context.get(JavaFileManager.class);
        verbose = options.get(OptionName.VERBOSE) != null;
        isBootstrap = options.get(OptionName.BOOTSTRAPCEYLON) != null;
        chk = Check.instance(context);
        types = Types.instance(context);
        symtab = Symtab.instance(context);
        todo = Todo.instance(context);
        annotate = Annotate.instance(context);
        taskListener = context.get(TaskListener.class);

        // now superclass init
        init(context);
    }

    @Override
    public void main(List<JCCompilationUnit> trees) {
        // complete the javac AST with a completed ceylon model
        timer.startTask("prepareForTypeChecking");
        compilerDelegate.prepareForTypeChecking(trees);
        timer.endTask();
        List<JCCompilationUnit> javaTrees = List.nil();
        List<JCCompilationUnit> ceylonTrees = List.nil();
        // split them in two sets: java and ceylon
        for(JCCompilationUnit tree : trees){
            if(tree instanceof CeylonCompilationUnit)
                ceylonTrees = ceylonTrees.prepend(tree);
            else
                javaTrees = javaTrees.prepend(tree);
        }
        timer.startTask("Enter on Java trees");
        // enter java trees first to set up their ClassSymbol objects for ceylon trees to use during type-checking
        if(isBootstrap){
            super.main(trees);
        }else if(!javaTrees.isEmpty()){
            super.main(javaTrees);
        }
        // now we can type-check the Ceylon code
        completeCeylonTrees(trees);
        if(isBootstrap){
            // bootstrapping the language module is a bit more complex
            resetAndRunEnterAgain(trees);
        }else{
            timer.startTask("Enter on Ceylon trees");
            // and complete their new trees
            try {
                Context.SourceLanguage.push(Language.CEYLON);
                super.main(ceylonTrees);                    
            } finally {
                Context.SourceLanguage.pop();
            }
            timer.endTask();
        }
    }

    private void resetAndRunEnterAgain(List<JCCompilationUnit> trees) {
        timer.startTask("Resetting all trees for bootstrap");
        
        // get rid of some caches and state
        chk.compiled.clear();
        types.reset();
        annotate.reset();
        super.reset();
        
        // reset all class symbols
        for(ClassSymbol classSymbol : symtab.classes.values()){
            if(Util.isLoadedFromSource(classSymbol) 
                    || (classSymbol.sourcefile != null && classSymbol.sourcefile.getKind() == Kind.SOURCE)){
                PackageSymbol pkg = classSymbol.packge();
                String name = pkg.getQualifiedName().toString();
                if(name.startsWith(AbstractModelLoader.CEYLON_LANGUAGE) || name.startsWith("com.redhat.ceylon.compiler.java"))
                    resetClassSymbol(classSymbol);
            }
        }
        
        // reset the trees
        JCTypeResetter jcTypeResetter = new JCTypeResetter();
        for(JCCompilationUnit tree : trees){
            tree.accept(jcTypeResetter);
        }
        
        // and reset the list of things to compile, because we got rid of the Env key we used to look them up
        // so they'd appear as extra things to compile when we do Enter
        todo.reset();
        timer.endTask();
        
        timer.startTask("Enter on Java+Ceylon trees");
        // now do Enter on all the java+ceylon code
        super.main(trees);
        timer.endTask();
    }

    /**
     * This resets a ClassSymbol recursively, for bootstrap
     */
    private void resetClassSymbol(ClassSymbol classSymbol) {
        // look for inner classes
        if(classSymbol.members_field != null){
            for(Symbol member : classSymbol.getEnclosedElements()){
                if(member instanceof ClassSymbol){
                    resetClassSymbol((ClassSymbol)member);
                }
            }
        }
        
        // reset its type, we need to keep it
        Type.ClassType classType = (ClassType) classSymbol.type;
        classType.all_interfaces_field = null;
        classType.interfaces_field = null;
        classType.supertype_field = null;
        classType.typarams_field = null;
        
        // reset its members and completer
        classSymbol.members_field = null;
        classSymbol.completer = null;
    }

    @Override
    protected Type classEnter(JCTree tree, Env<AttrContext> env) {
        if(tree instanceof CeylonCompilationUnit){
            Context.SourceLanguage.push(Language.CEYLON);
            try{
                return super.classEnter(tree, env);
            }finally{
                Context.SourceLanguage.pop();
            }
        }else
            return super.classEnter(tree, env);
    }
    
    public void prepareForTypeChecking(List<JCCompilationUnit> trees) {
        if (hasRun)
            throw new RuntimeException("Waaaaa, running twice!!!");
        //By now le language module version should be known (as local)
        //or we should use the default one.
        Module languageModule = ceylonContext.getModules().getLanguageModule();
        if (languageModule.getVersion() == null) {
            languageModule.setVersion(TypeChecker.LANGUAGE_MODULE_VERSION);
        }
        // load the standard modules
        timer.startTask("loadStandardModules");
        modelLoader.loadStandardModules();
        timer.endTask();
        // load the modules we are compiling first
        hasRun = true;
        // make sure we don't load the files we are compiling from their class files
        timer.startTask("setupSourceFileObjects");
        modelLoader.setupSourceFileObjects(trees);
        timer.endTask();
        // resolve module dependencies
        timer.startTask("verifyModuleDependencyTree");
        ModuleValidator validator = new ModuleValidator(ceylonContext, phasedUnits);
        validator.verifyModuleDependencyTree();
        timer.endTask();
        // now load package descriptors
        timer.startTask("loadPackageDescriptors");
        modelLoader.loadPackageDescriptors();
        timer.endTask();
        // at this point, abort if we had any errors logged
        timer.startTask("collectTreeErrors");
        collectTreeErrors(false);
        timer.endTask();
        // check if we abort on errors or not
        if (options.get(OptionName.CEYLONCONTINUE) == null) {
            // if we didn't have any errors, we can go on, none were logged so
            // they can't be re-logged and duplicated later on
            if(log.nerrors > 0)
                throw new Abort();
        }
    }
    
    public void completeCeylonTrees(List<JCCompilationUnit> trees){
        // run the type checker
        timer.startTask("Ceylon type checking");
        typeCheck();
        // some debugging
        //printModules();
        timer.startTask("Ceylon code generation");
        /*
         * Here we convert the ceylon tree to its javac AST, after the typechecker has run
         */
        Timer nested = timer.nestedTimer();
        for (JCCompilationUnit tree : trees) {
            if (tree instanceof CeylonCompilationUnit) {
                CeylonCompilationUnit ceylonTree = (CeylonCompilationUnit) tree;
                gen.setMap(ceylonTree.lineMap);
                CeylonPhasedUnit phasedUnit = (CeylonPhasedUnit)ceylonTree.phasedUnit;
                gen.setFileObject(phasedUnit.getFileObject());
                nested.startTask("Ceylon code generation for " + phasedUnit.getUnitFile().getName());
                TaskEvent event = new TaskEvent(TaskEvent.Kind.PARSE, tree);
                if (taskListener != null) {
                    taskListener.started(event);
                }
                ceylonTree.defs = gen.transformAfterTypeChecking(ceylonTree.ceylonTree).toList();
                if (taskListener != null) {
                    taskListener.finished(event);
                }
                nested.endTask();
                if(isVerbose("ast")){
                    System.err.println("Model tree for "+tree.getSourceFile());
                    System.err.println(ceylonTree.ceylonTree);
                }
                if(isVerbose("code")){
                    System.err.println("Java code generated for "+tree.getSourceFile());
                    System.err.println(ceylonTree);
                }
            }
        }
        timer.startTask("Ceylon error generation");
        printGeneratorErrors();
        timer.endTask();
        // write some stats
        if(verbose)
            modelLoader.printStats();
    }

    private boolean isVerbose(String key) {
        return verbose || options.get(OptionName.VERBOSE + ":" + key) != null;
    }

    public void addOutputModuleToClassPath(Module module){
        RepositoryManager repositoryManager = fileManager.getOutputRepositoryManager();
        ArtifactResult artifact = null;
        try {
            ArtifactContext ctx = new ArtifactContext(module.getNameAsString(), module.getVersion(), ArtifactContext.CAR, ArtifactContext.JAR);
            artifact = repositoryManager.getArtifactResult(ctx);
        } catch (InvalidArchiveException e) {
            log.error("ceylon", "Module car " + e.getPath()
                    +" obtained from repository " + e.getRepository()
                    +" has an invalid SHA1 signature: you need to remove it and rebuild the archive, since it"
                    +" may be corrupted.");
        } catch (Exception e) {
            String moduleName = module.getNameAsString();
            if(!module.isDefault())
                moduleName += "/" + module.getVersion();
            log.error("ceylon", "Exception occured while trying to resolve module "+moduleName);
            e.printStackTrace();
        }
        addModuleToClassPath(module, false, artifact);
    }
    
    public boolean isModuleInClassPath(Module module){
        return modulesAddedToClassPath.contains(module);
    }
    
    public void addModuleToClassPath(Module module, boolean errorIfMissing, ArtifactResult result) {
        if(verbose)
            Log.printLines(log.noticeWriter, "[Adding module to classpath: "+module.getNameAsString()+"/"+module.getVersion()+"]");        
        
        Paths.Path classPath = paths.getPathForLocation(StandardLocation.CLASS_PATH);

        File artifact = null;
        try {
            artifact = result != null ? result.artifact() : null;
        } catch (Exception e) {
            String moduleName = module.getNameAsString();
            if(!module.isDefault())
                moduleName += "/" + module.getVersion();
            log.error("ceylon", "Exception occured while trying to resolve module "+moduleName);
            e.printStackTrace();
        }
        
        if(verbose){
            if(artifact != null)
                Log.printLines(log.noticeWriter, "[Found module at : "+artifact.getPath()+"]");
            else
                Log.printLines(log.noticeWriter, "[Could not find module]");
        }

        if(modulesAddedToClassPath.add(module)){
            if(artifact != null && artifact.exists()){
                classPath.add(artifact);
                ((LazyModule)module).loadPackageList(result);
            }else if(errorIfMissing){
                log.error("ceylon", "Failed to find module "+module.getNameAsString()+"/"+module.getVersion()+" in repositories");
            }
        }else if(verbose){
            Log.printLines(log.noticeWriter, "[Module already added to classpath]");
        }
    }

    private void typeCheck() {
        final java.util.List<PhasedUnit> listOfUnits = phasedUnits.getPhasedUnits();

        // Delegate to an external typechecker (e.g. the IDE build)
        compilerDelegate.typeCheck(listOfUnits);

        // This phase is proper to the Java backend 
        for (PhasedUnit pu : listOfUnits) {
            Unit unit = pu.getUnit();
            final CompilationUnit compilationUnit = pu.getCompilationUnit();
            for (Declaration d: unit.getDeclarations()) {
                if (d instanceof TypedDeclaration && !(d instanceof Setter)) {
                    compilationUnit.visit(new MethodOrValueReferenceVisitor((TypedDeclaration) d));
                }
            }
        }
        
        UnsupportedVisitor uv = new UnsupportedVisitor();
        MissingNativeVisitor mnv = new MissingNativeVisitor(modelLoader);
        BoxingDeclarationVisitor boxingDeclarationVisitor = new CompilerBoxingDeclarationVisitor(gen);
        BoxingVisitor boxingVisitor = new CompilerBoxingVisitor(gen);
        DeferredVisitor deferredVisitor = new DeferredVisitor();
        AnnotationModelVisitor amv = new AnnotationModelVisitor(gen);
        DefiniteAssignmentVisitor dav = new DefiniteAssignmentVisitor();
        TypeParameterCaptureVisitor tpCaptureVisitor = new TypeParameterCaptureVisitor();
        InterfaceVisitor localInterfaceVisitor = new InterfaceVisitor();
        // Extra phases for the compiler
        
        // boxing visitor depends on boxing decl
        for (PhasedUnit pu : listOfUnits) {
            pu.getCompilationUnit().visit(uv);
        }
        for (PhasedUnit pu : listOfUnits) {
            pu.getCompilationUnit().visit(boxingDeclarationVisitor);
        }
        // the others can run at the same time
        for (PhasedUnit pu : listOfUnits) {
            CompilationUnit compilationUnit = pu.getCompilationUnit();
            compilationUnit.visit(mnv);
            compilationUnit.visit(boxingVisitor);
            compilationUnit.visit(deferredVisitor);
            compilationUnit.visit(amv);
            compilationUnit.visit(dav);
            compilationUnit.visit(tpCaptureVisitor);
            compilationUnit.visit(localInterfaceVisitor);
        }
        
        collectTreeErrors(true);
    }

    private void collectTreeErrors(boolean runAssertions) {
        final java.util.List<PhasedUnit> listOfUnits = phasedUnits.getPhasedUnits();

        for (PhasedUnit pu : listOfUnits) {
            pu.getCompilationUnit().visit(new JavacAssertionVisitor((CeylonPhasedUnit) pu, runAssertions){
                @Override
                protected void out(UnexpectedError err) {
                    logError(getPosition(err.getTreeNode()), "ceylon", err.getMessage());
                }
                @Override
                protected void out(AnalysisError err) {
                    Node node = getIdentifyingNode(err.getTreeNode());
                    logError(getPosition(node), "ceylon", err.getMessage());
                }
                @Override
                protected void out(UnsupportedError err) {
                    Node node = getIdentifyingNode(err.getTreeNode());
                    logError(getPosition(node), "ceylon", err.getMessage());
                }
                @Override
                protected void out(Node that, String message) {
                    logError(getPosition(that), "ceylon", message);
                }
            });
        }
    }

    /**
     * Visits the nodes of each unit calling 
     * {@link #logError(int, String, String)} for each {@link CodeGenError}
     */
    private void printGeneratorErrors() {
        final java.util.List<PhasedUnit> listOfUnits = phasedUnits.getPhasedUnits();

        for (PhasedUnit pu : listOfUnits) {
            pu.getCompilationUnit().visit(new JavacAssertionVisitor((CeylonPhasedUnit) pu, false){
                @Override
                protected void out(UnexpectedError err) {
                    if(err instanceof CodeGenError){
                        CodeGenError error = ((CodeGenError)err);
                        logError(getPosition(err.getTreeNode()), "ceylon.codegen.exception", "Uncaught exception during code generation: "+error.getCause());
                        logStackTrace("ceylon.codegen.exception", error.getCause());
                    }
                }
                // Ignore those
                @Override
                protected void out(AnalysisError err) {}
                @Override
                protected void out(UnsupportedError err) {}
                @Override
                protected void out(Node that, String message) {}
            });
        }
    }

    protected void logError(int position, String key, String message) {
        boolean prev = log.multipleErrors;
        // we want multiple errors for Ceylon
        log.multipleErrors = true;
        try{
            log.error(position, key, message);
        }finally{
            log.multipleErrors = prev;
        }
    }

    protected void logWarning(int position, String key, String message) {
        boolean prev = log.multipleErrors;
        // we want multiple errors for Ceylon
        log.multipleErrors = true;
        try{
            log.warning(position, key, message);
        }finally{
            log.multipleErrors = prev;
        }
    }
    
    protected void logStackTrace(String key, Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.close();
        String tracktrace = sw.toString();
        boolean prev = log.multipleErrors;
        // we want multiple errors for Ceylon
        log.multipleErrors = true;
        try{
            log.printErrLines(key, tracktrace);
        }finally{
            log.multipleErrors = prev;
        }
    }

    private class JavacAssertionVisitor extends AssertionVisitor {
        private CeylonPhasedUnit cpu;
        protected final boolean runAssertions;
        JavacAssertionVisitor(CeylonPhasedUnit cpu, boolean runAssertions){
            this.cpu = cpu;
            this.runAssertions = runAssertions;
        }
        
        @Override
        protected void checkType(Statement that, ProducedType type, Node typedNode) {
            if(runAssertions)
                super.checkType(that, type, typedNode);
        }
        
        protected Node getIdentifyingNode(Node node) {
            Node result = null;
            if (node instanceof Tree.Declaration) {
                result = ((Tree.Declaration) node).getIdentifier();
            }
            else if (node instanceof Tree.ModuleDescriptor) {
                result = ((Tree.ModuleDescriptor) node).getImportPath();
            }
            else if (node instanceof Tree.PackageDescriptor) {
                result = ((Tree.PackageDescriptor) node).getImportPath();
            }
            else if (node instanceof Tree.NamedArgument) {
                result = ((Tree.NamedArgument) node).getIdentifier();
            }
            else if (node instanceof Tree.StaticMemberOrTypeExpression) {
                result = ((Tree.StaticMemberOrTypeExpression) node).getIdentifier();
            }
            else if (node instanceof Tree.ExtendedTypeExpression) {
                //TODO: whoah! this is really ugly!
                result = ((Tree.SimpleType) ((Tree.ExtendedTypeExpression) node).getChildren().get(0))
                        .getIdentifier();
            }
            else if (node instanceof Tree.SimpleType) {
                result = ((Tree.SimpleType) node).getIdentifier();
            }
            else if (node instanceof Tree.ImportMemberOrType) {
                result = ((Tree.ImportMemberOrType) node).getIdentifier();
            }
            else {
                result = node;
            }
            if (result == null) {
                result = node;
            }
            return result;
        }
        protected int getPosition(Node node) {
            int pos;
            if(node != null && node.getToken() != null)
                pos = cpu.getLineMap().getStartPosition(node.getToken().getLine())
                + node.getToken().getCharPositionInLine();
            else
                pos = -1;
            log.useSource(cpu.getFileObject());
            return pos;
        }
        @Override
        protected void initExpectingError(java.util.List<CompilerAnnotation> annotations) {
            if (runAssertions) {
                super.initExpectingError(annotations);
            }
        }
    }
    
    public boolean hasRun(){
        return hasRun;
    }
}
