package com.redhat.ceylon.ceylondoc;

import static com.redhat.ceylon.ceylondoc.CeylonDoc.getPackage;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

import com.redhat.ceylon.compiler.typechecker.model.Class;
import com.redhat.ceylon.compiler.typechecker.model.ClassOrInterface;
import com.redhat.ceylon.compiler.typechecker.model.Declaration;
import com.redhat.ceylon.compiler.typechecker.model.Element;
import com.redhat.ceylon.compiler.typechecker.model.IntersectionType;
import com.redhat.ceylon.compiler.typechecker.model.Module;
import com.redhat.ceylon.compiler.typechecker.model.Package;
import com.redhat.ceylon.compiler.typechecker.model.ProducedType;
import com.redhat.ceylon.compiler.typechecker.model.Scope;
import com.redhat.ceylon.compiler.typechecker.model.TypeDeclaration;
import com.redhat.ceylon.compiler.typechecker.model.TypeParameter;
import com.redhat.ceylon.compiler.typechecker.model.UnionType;

public class LinkRenderer {
    
    private StringBuffer buffer = new StringBuffer();
    private Object to;
    private Object from;
    private DocTool ceylonDocTool;
    private Writer writer;
    private String customText;
    private Scope scope;
    private boolean skipTypeArguments;
    
    public LinkRenderer(DocTool ceylonDocTool, Writer writer, Object from) {
        this.ceylonDocTool = ceylonDocTool;
        this.writer = writer;
        this.from = from;
    }
    
    public LinkRenderer to(String declarationName) {
        to = declarationName;
        return this;
    }

    public LinkRenderer to(Declaration declaration) {
        to = declaration;
        return this;
    }

    public LinkRenderer to(ProducedType producedType) {
        to = producedType;
        return this;
    }

    public LinkRenderer to(Module module) {
        to = module;
        return this;
    }

    public LinkRenderer to(Package pkg) {
        to = pkg;
        return this;
    }
    
    public LinkRenderer useCustomText(String customText) {
        this.customText = customText;
        return this;
    }
    
    public LinkRenderer useScope(Module module) {
        scope = module.getPackage(module.getNameAsString());
        return this;
    }

    public LinkRenderer useScope(Package pkg) {
        scope = pkg;
        return this;
    }

    public LinkRenderer useScope(Declaration decl) {
        scope = resolveScope(decl);
        return this;
    }
    
    public LinkRenderer skipTypeArguments() {
        this.skipTypeArguments = true;
        return this;
    }
    
    public String getAnchor() {
        try {
            if (to instanceof String) {
                processDeclarationLink((String) to);
            } else if (to instanceof ProducedType) {
                processProducedType((ProducedType) to);
            } else if (to instanceof IntersectionType) {
                processIntersectionType((IntersectionType) to);
            } else if (to instanceof UnionType) {
                processUnionType((UnionType) to);
            } else if (to instanceof ClassOrInterface) {
                processClassOrInterface((ClassOrInterface) to);
            } else if (to instanceof Declaration) {
                processDeclaration((Declaration) to);
            } else if (to instanceof Module) {
                processModule((Module) to);
            } else if (to instanceof Package) {
                processPackage((Package) to);
            }
            return buffer.toString();
        } finally {
            buffer.setLength(0);
        }
    }
    
    public String getUrl() {
        return getUrl(to);
    }
    
    public String getResourceUrl(String to) throws IOException {
        return ceylonDocTool.getResourceUrl(from, to);
    }

    public String getSrcUrl(Object to) throws IOException {
        return ceylonDocTool.getSrcUrl(from, to);
    }

    public void write() throws IOException {
        String link = getAnchor();
        writer.write(link);
    }

    private void processModule(Module module) {
        String moduleUrl = getUrl(module);
        appendAnchor(moduleUrl, module.getNameAsString());
    }
    
    private void processPackage(Package pkg) {
        String pkgUrl = getUrl(pkg);
        appendAnchor(pkgUrl, pkg.getNameAsString());
    }

    private void processProducedType(ProducedType producedType) {
        if (producedType != null) {
            TypeDeclaration typeDeclaration = producedType.getDeclaration();
            if (typeDeclaration instanceof IntersectionType) {
                processIntersectionType((IntersectionType) typeDeclaration);
            } else if (typeDeclaration instanceof UnionType) {
                processUnionType((UnionType) typeDeclaration);
            } else if (typeDeclaration instanceof ClassOrInterface) {
                processClassOrInterface((ClassOrInterface) typeDeclaration);
            } else if (typeDeclaration instanceof TypeParameter) {
                buffer.append("<span class='type-parameter'>").append(typeDeclaration.getName()).append("</span>");
            } else {
                buffer.append(producedType.getProducedTypeName());
            }
        }
    }

    private void processIntersectionType(IntersectionType intersectionType) {
        boolean first = true;
        for (ProducedType st : intersectionType.getSatisfiedTypes()) {
            if (first) {
                first = false;
            } else {
                buffer.append("&amp;");
            }
            processProducedType(st);
        }
    }

    private void processUnionType(UnionType unionType) {
        if( isOptionalTypeAbbreviation(unionType) ) {
            ProducedType nonOptionalType = getNonOptionalTypeForDisplay(unionType);
            processProducedType(nonOptionalType);
            buffer.append("?");
            return;
        }
        if( isSequenceTypeAbbreviation(unionType) ) {
            ProducedType elementType = unionType.getUnit().getElementType(unionType.getType());
            processProducedType(elementType);
            buffer.append("[]");
            return;
        }
        
        boolean first = true;
        for (ProducedType producedType : unionType.getCaseTypes()) {
            if (first) {
                first = false;
            } else {
                buffer.append("|");
            }
            processProducedType(producedType);
        }        
    }

    private void processClassOrInterface(ClassOrInterface clazz) {
        String clazzName = clazz.getName();
        if (isInCurrentModule(clazz)) {
            String clazzUrl = getUrl(clazz);
            appendAnchor(clazzUrl, clazzName);
        } else {
            buffer.append(clazzName);
        }

        if( !skipTypeArguments ) {
            List<TypeParameter> typeParameters = clazz.getTypeParameters();
            if( typeParameters != null && !typeParameters.isEmpty() ) {
                buffer.append("&lt;");
                boolean first = true;
                for (TypeParameter typeParam : typeParameters) {
                    if (first) {
                        first = false;
                    } else {
                        buffer.append(",");
                    }
                    processProducedType(typeParam.getType());
                }
                buffer.append("&gt;");
            }
        }
    }
    
    private void processDeclaration(Declaration decl) {
        String declName = decl.getName();
        Scope declContainer = decl.getContainer();

        if (isInCurrentModule(declContainer)) {
            String sectionPackageAnchor = "#section-package";
            String containerUrl = getUrl(declContainer);
            if (containerUrl.endsWith(sectionPackageAnchor)) {
                containerUrl = containerUrl.substring(0, containerUrl.length() - sectionPackageAnchor.length());
            }
            String declUrl = containerUrl + "#" + declName;
            
            appendAnchor(declUrl, declName);
        } else {
            buffer.append(declName);
        }
    }

    private void processDeclarationLink(String declLink) {
        String declName;
        Scope currentScope;
        
        int pkgSeparatorIndex = declLink.indexOf("@");
        if( pkgSeparatorIndex == -1 ) {
            declName = declLink;
            currentScope = scope;
        } else {
            String pkgName = declLink.substring(0, pkgSeparatorIndex);
            declName = declLink.substring(pkgSeparatorIndex+1, declLink.length());
            currentScope = ceylonDocTool.getCurrentModule().getPackage(pkgName);
        }
        
        String[] declNames = declName.split("\\.");
        Declaration currentDecl = null;
        boolean isNested = false;
        for (String currentDeclName : declNames) {
            currentDecl = resolveDeclaration(currentScope, currentDeclName, isNested);
            if (currentDecl != null) {
                currentScope = resolveScope(currentDecl);
                isNested = true;
            } else {
                break;
            }
        }
    
        if (currentDecl != null) {
            if (currentDecl instanceof ClassOrInterface) {
                processClassOrInterface((ClassOrInterface) currentDecl);
            } else {
                processDeclaration(currentDecl);
            }
        } else {
            buffer.append(declLink);
        }
    }

    private Declaration resolveDeclaration(Scope scope, String declName, boolean isNested) {
        Declaration decl = null;

        if (scope != null) {
            decl = scope.getMember(declName, null);

            if (decl == null && !isNested && scope instanceof Element) {
                decl = ((Element) scope).getUnit().getImportedDeclaration(declName, null);
            }

            if (decl == null && !isNested) {
                decl = resolveDeclaration(scope.getContainer(), declName, isNested);
            }
        }

        return decl;
    }

    private Scope resolveScope(Declaration decl) {
        if (decl == null) {
            return null;
        } else if (decl instanceof Scope) {
            return (Scope) decl;
        } else {
            return decl.getContainer();
        }
    }

    private boolean isOptionalTypeAbbreviation(UnionType unionType) {
        return unionType.getCaseTypes().size() == 2 &&
                com.redhat.ceylon.compiler.typechecker.model.Util.isElementOfUnion(
                        unionType, unionType.getUnit().getNothingDeclaration());
    }

    private boolean isSequenceTypeAbbreviation(UnionType unionType) {
        return unionType.getCaseTypes().size() == 2 &&
                com.redhat.ceylon.compiler.typechecker.model.Util.isElementOfUnion(
                        unionType, unionType.getUnit().getEmptyDeclaration()) &&
                com.redhat.ceylon.compiler.typechecker.model.Util.isElementOfUnion(
                        unionType, unionType.getUnit().getSequenceDeclaration());
    }

    private boolean isInCurrentModule(Scope scope) {
        Module currentModule = ceylonDocTool.getCurrentModule();
        if (currentModule != null) {
            return currentModule.equals(getPackage(scope).getModule());
        }
        return false;
    }

    /**
     * When parameter is <code>UnionType[Element?]</code>, we can not use method <code>Unit.getDefiniteType()</code>, 
     * because its result is <code>IntersectionType[Element&Object]</code> and to html is rendered <code>Element&Object?</code>.
     */
    private ProducedType getNonOptionalTypeForDisplay(UnionType unionType) {
        ProducedType nonOptionalType = null;
        Class nothingDeclaration = unionType.getUnit().getNothingDeclaration();
        for (ProducedType ct : unionType.getCaseTypes()) {
            TypeDeclaration ctd = ct.getDeclaration();
            if (ctd instanceof Class && ctd.equals(nothingDeclaration)) {
                continue;
            } else {
                nonOptionalType = ct;
                break;
            }
        }
        return nonOptionalType;
    }

    private String getUrl(Object to) {
        try {
            return ceylonDocTool.getObjectUrl(from, to);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void appendAnchor(String url, String text) {
        buffer.append("<a href='").append(url).append("'>");
        if( customText != null ) {
            buffer.append(customText);
        } else {
            buffer.append(text);
        }
        buffer.append("</a>");
    }

}