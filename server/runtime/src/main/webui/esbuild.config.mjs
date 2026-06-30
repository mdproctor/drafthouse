import { build, context } from "esbuild";
import { copyFileSync, mkdirSync } from "fs";

const isWatch = process.argv.includes("--watch");

mkdirSync("dist", { recursive: true });
copyFileSync("public/index.html", "dist/index.html");

const options = {
  entryPoints: ["src/index.ts"],
  bundle: true,
  outfile: "dist/app.js",
  format: "esm",
  target: "es2022",
  minify: false,
  sourcemap: true,
};

if (isWatch) {
  const ctx = await context(options);
  await ctx.watch();
  console.log("Watching for changes...");
} else {
  await build(options);
}
