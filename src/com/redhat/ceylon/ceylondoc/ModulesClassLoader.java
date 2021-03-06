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
package com.redhat.ceylon.ceylondoc;

import java.util.List;

import com.redhat.ceylon.cmr.api.ArtifactResult;
import com.redhat.ceylon.compiler.loader.impl.reflect.CachedTOCJars;

/**
 * Class loader which looks into a list of jar files
 */
class ModulesClassLoader extends ClassLoader {

    private CachedTOCJars jars = new CachedTOCJars();
    
    public ModulesClassLoader(ClassLoader parent) {
        super(parent);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String path = name.replace('.', '/').concat(".class");
        byte[] contents = jars.getContents(path);
        if(contents != null)
            return defineClass(name, contents, 0, contents.length);
        return super.findClass(name);
    }

    public void addJar(ArtifactResult artifact, boolean skipContents) {
        jars.addJar(artifact, skipContents);
    }

    public boolean packageExists(String name) {
        return jars.packageExists(name);
    }

    public List<String> getPackageList(String name) {
        return jars.getPackageList(name);
    }

}