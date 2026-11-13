import { readFileSync } from "node:fs";
import ts from "typescript";

const source = ts.createSourceFile(
  "App.tsx", readFileSync(new URL("../src/App.tsx", import.meta.url), "utf8"),
  ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX,
);

// Execute the actual controller closure with explicit fake services, without
// mounting Tauri or asserting a particular spelling of its implementation.
export function loadAppFunction(name, bindings = {}) {
  let declaration;
  function visit(node) {
    if ((ts.isFunctionDeclaration(node) || ts.isVariableDeclaration(node))
        && node.name?.getText(source) === name) declaration = node;
    ts.forEachChild(node, visit);
  }
  visit(source);
  if (!declaration) throw new Error(`App controller not found: ${name}`);
  const text = ts.isVariableDeclaration(declaration)
    ? `const ${declaration.getText(source)};`
    : declaration.getText(source);
  const js = ts.transpileModule(text, {
    compilerOptions: { target: ts.ScriptTarget.ES2020 },
  }).outputText;
  return new Function(...Object.keys(bindings), `${js}\nreturn ${name};`)(...Object.values(bindings));
}

export async function loadTypeScriptModule(relativePath) {
  const js = ts.transpileModule(readFileSync(new URL(relativePath, import.meta.url), "utf8"), {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 },
  }).outputText;
  return import(`data:text/javascript;base64,${Buffer.from(js).toString("base64")}`);
}
