"""Fictional Vietnamese identities for testing; not a directory of real customers."""
FAMILIES = ('Nguyễn','Trần','Lê','Phạm','Hoàng','Huỳnh','Phan','Vũ','Võ','Đặng','Bùi','Đỗ','Hồ','Ngô','Dương','Lý','Đinh','Trịnh','Đoàn','Mai')
GIVEN = ('Minh Quân','Gia Bảo','Đức Huy','Quốc An','Thành Đạt','Hoàng Nam','Anh Tuấn','Minh Khang','Trung Kiên','Thanh Tùng','Hoàng Phúc','Đức Thành','Văn Long','Minh Khôi','Quang Huy','Hữu Nghĩa','Thanh Phong','Bảo Long','Tuấn Anh','Nhật Minh','Thu Hà','Ngọc Mai','Khánh Linh','Minh Anh','Hoài Thương','Bảo Ngọc','Phương Thảo','Thanh Trúc','Hồng Nhung','Kim Ngân','Thùy Dương','Ngọc Hân','Quỳnh Anh','Gia Hân','Thảo Nguyên','Diệu Linh','Tú Uyên','Hải Yến','Ánh Tuyết','Mỹ Duyên')


def identities(rng):
    names=[family+' '+given for family in FAMILIES for given in GIVEN]
    rng.shuffle(names)
    return iter(names)


def phone(rng):
    return rng.choice(('090','091','093','094','096','097','098'))+f'{rng.randrange(10000000):07}'
