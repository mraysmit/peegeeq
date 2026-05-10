#!/usr/bin/env python3
"""
Fix-up script: removes misplaced logger.info lines from the first script,
then re-adds them at the correct locations (right after method opening brace).
"""

import re
import os

def camel_to_readable(name):
    """Convert camelCase/PascalCase method name to readable string."""
    if name.startswith('test'):
        name = name[4:]
    result = re.sub(r'([A-Z])', r' \1', name).strip().lower()
    result = result.replace('_', ' ')
    return result

# Patterns that were added by the buggy script
SCRIPT_PATTERNS = [
    re.compile(r'^\s*logger\.info\("Setting up: configuring database and starting PeeGeeQManager"\);\s*$'),
    re.compile(r'^\s*logger\.info\("Tearing down: closing resources and manager"\);\s*$'),
    re.compile(r'^\s*logger\.info\("Test: [^"]+"\);\s*$'),
]

def is_script_added_line(line):
    return any(p.match(line) for p in SCRIPT_PATTERNS)

def process_file(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # Only process files that have our logger
    if 'LoggerFactory.getLogger' not in content:
        return False
    
    lines = content.split('\n')
    
    # Step 1: Remove all misplaced logger.info lines from the first script
    cleaned_lines = []
    removed = 0
    for line in lines:
        if is_script_added_line(line):
            removed += 1
            # Also remove a blank line after the removed line if present
            continue
        cleaned_lines.append(line)
    
    if removed == 0:
        print(f"  SKIP (no script-added lines found): {os.path.basename(filepath)}")
        return False
    
    print(f"  Removed {removed} misplaced lines from {os.path.basename(filepath)}")
    
    # Step 2: Re-add logger.info at correct locations
    result_lines = []
    i = 0
    while i < len(cleaned_lines):
        line = cleaned_lines[i]
        stripped = line.strip()
        
        # Detect @BeforeEach followed by setUp method
        if stripped == '@BeforeEach':
            result_lines.append(line)
            i += 1
            # Look for the setUp method
            while i < len(cleaned_lines):
                l = cleaned_lines[i]
                s = l.strip()
                if re.match(r'void\s+setUp\s*\(', s):
                    # Found setUp method
                    if '{' in s:
                        # Opening brace on same line
                        result_lines.append(l)
                        result_lines.append('        logger.info("Setting up: configuring database and starting PeeGeeQManager");')
                        i += 1
                    else:
                        # Opening brace on next line
                        result_lines.append(l)
                        i += 1
                        while i < len(cleaned_lines) and '{' not in cleaned_lines[i]:
                            result_lines.append(cleaned_lines[i])
                            i += 1
                        if i < len(cleaned_lines):
                            result_lines.append(cleaned_lines[i])
                            result_lines.append('        logger.info("Setting up: configuring database and starting PeeGeeQManager");')
                            i += 1
                    break
                else:
                    result_lines.append(l)
                    i += 1
            continue
        
        # Detect @AfterEach followed by tearDown method
        if stripped == '@AfterEach':
            result_lines.append(line)
            i += 1
            while i < len(cleaned_lines):
                l = cleaned_lines[i]
                s = l.strip()
                if re.match(r'void\s+tearDown\s*\(', s):
                    if '{' in s:
                        result_lines.append(l)
                        result_lines.append('        logger.info("Tearing down: closing resources and manager");')
                        i += 1
                    else:
                        result_lines.append(l)
                        i += 1
                        while i < len(cleaned_lines) and '{' not in cleaned_lines[i]:
                            result_lines.append(cleaned_lines[i])
                            i += 1
                        if i < len(cleaned_lines):
                            result_lines.append(cleaned_lines[i])
                            result_lines.append('        logger.info("Tearing down: closing resources and manager");')
                            i += 1
                    break
                else:
                    result_lines.append(l)
                    i += 1
            continue
        
        # Detect @Test (or @ParameterizedTest) followed by test method
        if stripped == '@Test' or stripped.startswith('@Test') or stripped.startswith('@ParameterizedTest'):
            test_annotation_lines = [line]
            i += 1
            # Collect subsequent annotations and blank lines
            while i < len(cleaned_lines):
                s2 = cleaned_lines[i].strip()
                if s2.startswith('@') or s2 == '':
                    test_annotation_lines.append(cleaned_lines[i])
                    i += 1
                else:
                    break
            
            # Now at the method signature
            if i < len(cleaned_lines):
                method_line = cleaned_lines[i]
                method_stripped = method_line.strip()
                method_match = re.match(r'(?:public\s+|protected\s+|private\s+)?void\s+(\w+)\s*\(', method_stripped)
                if method_match:
                    mname = method_match.group(1)
                    readable = camel_to_readable(mname)
                    
                    # Add all annotation lines
                    result_lines.extend(test_annotation_lines)
                    
                    if '{' in method_stripped:
                        # Brace on same line as method
                        result_lines.append(method_line)
                        result_lines.append(f'        logger.info("Test: {readable}");')
                        i += 1
                    else:
                        # Brace on subsequent line
                        result_lines.append(method_line)
                        i += 1
                        while i < len(cleaned_lines) and '{' not in cleaned_lines[i]:
                            result_lines.append(cleaned_lines[i])
                            i += 1
                        if i < len(cleaned_lines):
                            result_lines.append(cleaned_lines[i])
                            result_lines.append(f'        logger.info("Test: {readable}");')
                            i += 1
                    continue
                else:
                    # Not a regular method, just output lines
                    result_lines.extend(test_annotation_lines)
                    continue
            else:
                result_lines.extend(test_annotation_lines)
                continue
        
        result_lines.append(line)
        i += 1
    
    new_content = '\n'.join(result_lines)
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(new_content)
    
    print(f"  FIXED: {os.path.basename(filepath)}")
    return True

if __name__ == '__main__':
    base = r'c:\Users\mraysmit\dev\idea-projects\peegeeq'
    modules = [
        'peegeeq-native',
        'peegeeq-outbox',
        'peegeeq-examples',
        'peegeeq-examples-spring',
        'peegeeq-db',
    ]
    
    fixed = 0
    for module in modules:
        test_dir = os.path.join(base, module, 'src', 'test', 'java')
        if not os.path.isdir(test_dir):
            continue
        print(f"\n{module}:")
        for dirpath, dirnames, filenames in os.walk(test_dir):
            for fname in sorted(filenames):
                if fname.endswith('.java'):
                    fpath = os.path.join(dirpath, fname)
                    with open(fpath, 'r', encoding='utf-8') as f:
                        content = f.read()
                    if 'manager.start().await()' in content and any(is_script_added_line(l) for l in content.split('\n')):
                        if process_file(fpath):
                            fixed += 1
    
    print(f"\nDone. Fixed {fixed} files.")
