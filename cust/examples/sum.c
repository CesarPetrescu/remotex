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
