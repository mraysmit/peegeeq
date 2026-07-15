#!/usr/bin/env python3
"""
Add SLF4J logging to Java test files that have manager.start().await() but no logger.
Adds: imports, logger field, logger.info in setUp/tearDown/test methods.
"""

import re
import os
import sys

def camel_to_readable(name):
    """Convert camelCase/PascalCase method name to readable string."""
    # Remove 'test' prefix if present
    if name.startswith('test'):
        name = name[4:]
    # Insert spaces before capitals
    result = re.sub(r'([A-Z])', r' \1', name).strip().lower()
    # Clean up underscores
    result = result.replace('_', ' ')
    return result

def process_file(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # Skip if already has Logger
    if 'LoggerFactory.getLogger' in content:
        print(f"  SKIP (already has logger): {os.path.basename(filepath)}")
        return False
    
    # Skip if no manager.start().await()
    if 'manager.start().await()' not in content:
        print(f"  SKIP (no manager.start().await()): {os.path.basename(filepath)}")
        return False

    lines = content.split('\n')
    new_lines = []
    
    # Extract class name
    class_match = re.search(r'class\s+(\w+)', content)
    if not class_match:
        print(f"  SKIP (no class found): {os.path.basename(filepath)}")
        return False
    class_name = class_match.group(1)
    
    # Track state
    added_imports = False
    added_field = False
    in_method = False
    method_name = None
    brace_depth = 0
    
    i = 0
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()
        
        # Add imports after the last import statement
        if not added_imports and stripped.startswith('import ') and not stripped.startswith('import org.slf4j'):
            # Look ahead to find last import
            j = i
            while j < len(lines) - 1:
                next_stripped = lines[j + 1].strip()
                if next_stripped.startswith('import ') or next_stripped == '':
                    j += 1
                    if next_stripped != '' and not next_stripped.startswith('import '):
                        break
                else:
                    break
            # Find actual last import line
            last_import = i
            for k in range(i, j + 1):
                if lines[k].strip().startswith('import '):
                    last_import = k
            
            # Write lines up to and including last import
            while i <= last_import:
                new_lines.append(lines[i])
                i += 1
            new_lines.append('import org.slf4j.Logger;')
            new_lines.append('import org.slf4j.LoggerFactory;')
            added_imports = True
            continue
        
        # Add logger field after class declaration
        if not added_field and re.match(r'\s*class\s+' + re.escape(class_name), stripped):
            new_lines.append(line)
            i += 1
            # Consume the opening brace if it's on the same line
            if '{' in line:
                new_lines.append(f'    private static final Logger logger = LoggerFactory.getLogger({class_name}.class);')
                new_lines.append('')
                added_field = True
                continue
            # Otherwise look for opening brace on next line
            while i < len(lines):
                new_lines.append(lines[i])
                if '{' in lines[i]:
                    new_lines.append(f'    private static final Logger logger = LoggerFactory.getLogger({class_name}.class);')
                    new_lines.append('')
                    added_field = True
                    i += 1
                    break
                i += 1
            continue
        
        # Add logging to setUp method
        if re.match(r'\s*void\s+setUp\s*\(', stripped):
            new_lines.append(line)
            i += 1
            # Find the opening brace
            while i < len(lines) and '{' not in lines[i]:
                new_lines.append(lines[i])
                i += 1
            if i < len(lines):
                new_lines.append(lines[i])  # The brace line
                new_lines.append('        logger.info("Setting up: configuring database and starting PeeGeeQManager");')
                i += 1
            continue
        
        # Add logging to tearDown method
        if re.match(r'\s*void\s+tearDown\s*\(', stripped):
            new_lines.append(line)
            i += 1
            # Find the opening brace
            while i < len(lines) and '{' not in lines[i]:
                new_lines.append(lines[i])
                i += 1
            if i < len(lines):
                new_lines.append(lines[i])  # The brace line
                new_lines.append('        logger.info("Tearing down: closing resources and manager");')
                i += 1
            continue
        
        # Add logging to @Test methods
        if stripped == '@Test' or stripped.startswith('@Test'):
            new_lines.append(line)
            i += 1
            # Consume @DisplayName or other annotations
            while i < len(lines):
                s = lines[i].strip()
                if s.startswith('@') or s == '':
                    new_lines.append(lines[i])
                    i += 1
                else:
                    break
            # Now we should be at the method signature
            if i < len(lines):
                method_line = lines[i].strip()
                method_match = re.match(r'void\s+(\w+)\s*\(', method_line)
                if method_match:
                    mname = method_match.group(1)
                    readable = camel_to_readable(mname)
                    new_lines.append(lines[i])
                    i += 1
                    # Find opening brace
                    while i < len(lines) and '{' not in lines[i]:
                        new_lines.append(lines[i])
                        i += 1
                    if i < len(lines):
                        new_lines.append(lines[i])  # brace line
                        new_lines.append(f'        logger.info("Test: {readable}");')
                        i += 1
                    continue
            continue
        
        new_lines.append(line)
        i += 1
    
    new_content = '\n'.join(new_lines)
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(new_content)
    
    print(f"  UPDATED: {os.path.basename(filepath)}")
    return True

def find_test_files(root_dirs):
    """Find all Java test files containing manager.start().await() but no Logger."""
    files = []
    for root_dir in root_dirs:
        test_dir = os.path.join(root_dir, 'src', 'test', 'java')
        if not os.path.isdir(test_dir):
            continue
        for dirpath, dirnames, filenames in os.walk(test_dir):
            for fname in filenames:
                if fname.endswith('.java'):
                    fpath = os.path.join(dirpath, fname)
                    with open(fpath, 'r', encoding='utf-8') as f:
                        content = f.read()
                    if 'manager.start().await()' in content and 'LoggerFactory.getLogger' not in content:
                        files.append(fpath)
    return files

if __name__ == '__main__':
    base = r'c:\Users\mraysmit\dev\idea-projects\peegeeq'
    modules = [
        'peegeeq-native',
        'peegeeq-outbox',
        'peegeeq-examples',
        'peegeeq-examples-spring',
        'peegeeq-db',
        'peegeeq-bitemporal',
    ]
    
    root_dirs = [os.path.join(base, m) for m in modules]
    files = find_test_files(root_dirs)
    
    print(f"Found {len(files)} files needing logger:")
    for f in sorted(files):
        print(f"  {os.path.relpath(f, base)}")
    
    print("\nProcessing...")
    updated = 0
    for f in sorted(files):
        if process_file(f):
            updated += 1
    
    print(f"\nDone. Updated {updated} files.")
