# cust

`cust` is a small C-like interpreter written in Rust. It supports a practical subset of C:

- `int` variables
- assignments
- arithmetic: `+`, `-`, `*`, `/`, `%`
- comparisons: `==`, `!=`, `<`, `<=`, `>`, `>=`
- logical operators: `&&`, `||`, `!`
- blocks with lexical scopes
- `if` / `else`
- `while`
- `return`
- `print(...)` builtin

It intentionally does not compile or interpret full C. Pointers, structs, arrays, includes, macros, and user-defined functions are not implemented.

## Run

```sh
cargo run -- path/to/program.c
```

If no path is passed, `cust` runs a built-in demo program.

## Example

```c
int x = 0;
int total = 0;

while (x < 5) {
    total = total + x;
    x = x + 1;
}

if (total == 10) {
    print(total);
} else {
    print(0);
}
```

## Test

```sh
cargo test
```
